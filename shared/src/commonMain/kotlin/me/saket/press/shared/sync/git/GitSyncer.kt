package me.saket.press.shared.sync.git

import com.badoo.reaktive.observable.Observable
import com.badoo.reaktive.observable.combineLatest
import com.soywiz.klock.DateTime
import kotlinx.coroutines.Runnable
import me.saket.kgit.Git
import me.saket.kgit.GitAuthor
import me.saket.kgit.GitCommit
import me.saket.kgit.GitConfig
import me.saket.kgit.GitPullResult
import me.saket.kgit.GitRepository
import me.saket.kgit.GitTreeDiff
import me.saket.kgit.GitTreeDiff.Change.Add
import me.saket.kgit.GitTreeDiff.Change.Copy
import me.saket.kgit.GitTreeDiff.Change.Delete
import me.saket.kgit.GitTreeDiff.Change.Modify
import me.saket.kgit.GitTreeDiff.Change.Rename
import me.saket.kgit.PushResult.AlreadyUpToDate
import me.saket.kgit.PushResult.Failure
import me.saket.kgit.PushResult.Success
import me.saket.kgit.UtcTimestamp
import me.saket.kgit.abbreviated
import me.saket.press.PressDatabase
import me.saket.press.shared.db.NoteId
import me.saket.press.shared.settings.Setting
import me.saket.press.shared.sync.SyncState
import me.saket.press.shared.sync.SyncState.IN_FLIGHT
import me.saket.press.shared.sync.SyncState.PENDING
import me.saket.press.shared.sync.SyncState.SYNCED
import me.saket.press.shared.sync.Syncer
import me.saket.press.shared.sync.Syncer.Status.Disabled
import me.saket.press.shared.sync.Syncer.Status.Failed
import me.saket.press.shared.sync.Syncer.Status.Idle
import me.saket.press.shared.sync.Syncer.Status.InFlight
import me.saket.press.shared.sync.git.GitSyncer.CommitResult.Done
import me.saket.press.shared.sync.git.GitSyncer.CommitResult.Skipped
import me.saket.press.shared.sync.git.service.GitRepositoryInfo
import me.saket.press.shared.time.Clock

// TODO:
//  Stop ship
//   - broadcast an event when a merge conflict is resolved.
//  Others
//   - figure out git author name/email.
//   - set both author and committer time.
//   - commit deleted notes.
//   - show errors in status UI
class GitSyncer(
  git: Git,
  private val config: Setting<GitSyncerConfig>,
  private val database: PressDatabase,
  private val deviceInfo: DeviceInfo,
  private val clock: Clock,
  private val status: Setting<Status>
) : Syncer() {

  private val noteQueries get() = database.noteQueries
  private val directory = File(deviceInfo.appStorage, "git")
  private val register = FileNameRegister(directory)
  private val gitAuthor = GitAuthor("Saket", "pressapp@saket.me")
  private val remote: GitRepositoryInfo? get() = config.get()?.remote
  private val loggers = SyncLoggers(PrintLnSyncLogger)
  private val git = {
    val config = config.get()!!
    git.repository(
        path = directory.path,
        sshKey = config.sshKey,
        remoteSshUrl = config.remote.sshUrl,
        userConfig = GitConfig("diff" to listOf("renames" to "true"))
    )
  }

  override fun status(): Observable<Status> {
    return combineLatest(config.listen(), status.listen()) { config, status ->
      when (config) {
        null -> Disabled
        else -> status ?: Idle(lastSyncedAt = null)
      }
    }
  }

  override fun sync() {
    if (config.get() == null) {
      // Sync is disabled.
      return
    }

    status.set(InFlight)
    loggers.onSyncStart()
    directory.makeDirectory(recursively = true)

    try {
      with(GitScope(git())) {
        val pullResult = pull()
        val commitResult = commitAllChanges(pullResult)
        processCommits(pullResult)
        push(pullResult, commitResult)
      }

      status.set(Idle(lastSyncedAt = clock.nowUtc()))

    } catch (e: Throwable) {
      log("Error: ${e.message}")
      status.set(Failed)
      throw e

    } finally {
      loggers.onSyncComplete()
    }
  }

  class GitScope(val git: GitRepository)

  override fun disable() {
    config.set(null)
    directory.delete(recursively = true)
    noteQueries.swapSyncStates(old = SyncState.values().toList(), new = PENDING)
  }

  /** Commit announcing that syncing has been setup. */
  private fun maybeMakeInitialCommit(git: GitRepository) {
    if (git.headCommit() != null) {
      return
    }

    with(File(directory, ".press/")) {
      makeDirectory(recursively = true)
      File(this, "README.md").write(
          "Press uses files in this directory for storing meta-data of your synced notes. " +
              "They are auto-generated and shouldn't be modified. If you run into any " +
              "issues with syncing of notes, feel free to file a [bug report here]" +
              "(https://github.com/saket/press/issues) and attach [sync logs](sync_log.txt)" +
              " after removing/redacting any private info."
      )
    }

    git.commitAll(
        message = "Setup syncing on '${deviceInfo.deviceName()}'",
        author = gitAuthor,
        timestamp = UtcTimestamp(clock),
        allowEmpty = true
    )

    // JGit doesn't offer a way to set the initial branch name and it
    // won't let us change the branch without committing anything either
    // so we change it after committing something.
    git.checkout(remote!!.defaultBranch, create = true)
  }

  private data class PullResult(
    val headBefore: GitCommit,
    val headAfter: GitCommit
  )

  private fun GitScope.pull(): PullResult {
    check(!git.isStagingAreaDirty()) { "Expected staging area to be clean before pulling" }

    maybeMakeInitialCommit(git)
    val localHead = git.headCommit()!!  // non-null because of maybeMakeInitialCommit().

    return when (val it = git.pull(rebase = true)) {
      is GitPullResult.Success -> {
        val upstreamHead = git.headCommit()!!
        if (upstreamHead != localHead) {
          log("Pulled upstream. Moved head from $localHead to $upstreamHead.")
          val pullDiff = git.diffBetween(localHead, upstreamHead)
          if (pullDiff.isNotEmpty()) {
            log("\nPulled changes (${pullDiff.size}):")
            log(pullDiff.flattenToString())
          }

        } else {
          log("Nothing to pull.")
        }
        PullResult(headBefore = localHead, headAfter = upstreamHead)
      }
      is GitPullResult.Failure -> {
        throw error("Failed to rebase: $it")
      }
    }
  }

  private enum class CommitResult {
    Skipped,
    Done
  }

  @Suppress("CascadeIf")
  private fun GitScope.commitAllChanges(pullResult: PullResult): CommitResult {
    val pendingSyncNotes = noteQueries.notesInState(listOf(PENDING, IN_FLIGHT)).executeAsList()
    if (pendingSyncNotes.isEmpty()) {
      log("Nothing to commit.")
      return Skipped
    }

    // Having an intermediate sync state between PENDING and SYNCED
    // is important in case a note gets updated while it is syncing,
    // in which case it'll get marked as PENDING again.
    noteQueries.updateSyncState(
        ids = pendingSyncNotes.map { it.id },
        syncState = IN_FLIGHT
    )

    val pulledPathsToDiff = git
        .diffBetween(pullResult.headBefore, pullResult.headAfter)
        .filterNoteChanges()
        .associateBy { it.path }

    log("\nReading unsynced notes (${pendingSyncNotes.size}):")

    // Git makes it easy to handle merge conflicts, but automating it for
    // the user is going to be a challenge. If the same note was modified
    // from different devices, Press duplicates them: note.md & note_2.md.
    // This will also cover non-.md files.
    //
    // It's _very_ important that the local copy is duplicated. It'd be
    // nice to rename the upstream copy because it's possible that the
    // local copy is being edited right now, but duplicating the remote
    // copy will result in an infinite loop where a new copy is created
    // on every sync on the other device.
    //
    // These files will get processed after rebase.
    for (note in pendingSyncNotes) {
      val (noteFile, oldFile, acceptRename) = register.suggestFile(note)
      val notePath = noteFile.relativePathIn(directory)
      val oldPath = oldFile?.relativePathIn(directory)
      log(" • $notePath")

      if (notePath in pulledPathsToDiff && note.content != noteFile.read()) {
        // File's content is going to change in a conflicting way.
        noteFile.copy(register.findNewNameOnConflict(noteFile)).let {
          it.write(note.content)
          log("   duplicated to ${it.name} to resolve merge conflict")
        }

      } else if (oldPath in pulledPathsToDiff) {
        // Old path was updated on remote, but deleted locally.
        noteFile.write(note.content)
        log("   created as a new note to resolve merge conflict (old path = $oldPath)")

      } else {
        acceptRename?.invoke()
        noteFile.write(note.content)
        log("   created/updated")
      }

      // Staging area may not be dirty if this note had already been processed earlier.
      if(git.isStagingAreaDirty()) {
        git.commitAll(
            message = "Update '${noteFile.name}'",
            author = gitAuthor,
            timestamp = UtcTimestamp(note.updatedAt)
        )
      }
    }
    return Done
  }

  @Suppress("NAME_SHADOWING")
  private fun GitScope.processCommits(pullResult: PullResult) {
    if (pullResult.headBefore == pullResult.headAfter) {
      log("Nothing to process.")
      return
    }

    val to = git.headCommit()!!
    val from = git.commonAncestor(first = pullResult.headBefore, second = to)

    log("\nProcessing commits from ${from?.sha1?.abbreviated} to ${to.sha1.abbreviated}")

    val commits = git.commitsBetween(from = from, toInclusive = to)
    commits.forEach { log(" • ${it.sha1.abbreviated} - ${it.message.lines().first()}") }

    // Press stores updated-at timestamp of notes in each commit.
    val diffPathTimestamps = commits
        .flatMap { commit ->
          git.changesIn(commit)
              .filterNoteChanges()
              .map { it.path to commit.dateTime }
        }
        .toMap()

    // DB operations are executed in one go to
    // avoid locking the DB in a transaction for long.
    val dbOperations = mutableListOf<Runnable>()

    val diffs = git.diffBetween(from, to)

    log("\nProcessing changes (${diffs.size}):")
    if (diffs.isNotEmpty()) log(diffs.flattenToString())

    for (diff in diffs.filterNoteChanges()) {
      val commitTime = diffPathTimestamps[diff.path]!!

      dbOperations += when (diff) {
        is Copy,
        is Rename,
          // Renaming of note files are ignored. Press
          // generates a name as per the note's heading.
        is Add, is Modify -> {
          val file = File(directory, diff.path)
          val content = file.read()

          val oldPath = if (diff is Rename) diff.fromPath else null
          val record = register.recordFor(diff.path, oldPath = oldPath)
          val existingId = record?.noteId
          val isArchived = record?.noteFolder == "archived"

          val isNewNote = existingId == null || !noteQueries.exists(existingId).executeAsOne()

          if (isNewNote) {
            val newId = existingId ?: NoteId.generate()
            log("Creating new note $newId for (${diff.path}), isArchived? $isArchived")
            register.createNewRecordFor(file, newId)
            Runnable {
              noteQueries.insert(
                  id = newId,
                  content = content,
                  createdAt = commitTime,
                  updatedAt = commitTime
              )
              noteQueries.setArchived(
                  id = newId,
                  isArchived = isArchived,
                  updatedAt = commitTime
              )
              noteQueries.updateSyncState(
                  ids = listOf(newId),
                  syncState = IN_FLIGHT
              )
            }
          } else {
            log("Updating $existingId (${diff.path}), isArchived? $isArchived")
            check(existingId != null) { "existingId is null for an existing note" }
            Runnable {
              noteQueries.updateContent(
                  id = existingId,
                  content = content,
                  updatedAt = commitTime
              )
              noteQueries.setArchived(
                  id = existingId,
                  isArchived = isArchived,
                  updatedAt = commitTime
              )
              noteQueries.updateSyncState(
                  ids = listOf(existingId),
                  syncState = IN_FLIGHT
              )
            }
          }
        }
        is Delete -> {
          val noteId = register.noteIdFor(diff.path)
          if (noteId == null) {
            // Commit has already been processed earlier.
            Runnable {}
          } else {
            log("Permanently deleting $noteId (${diff.path})")
            Runnable {
              noteQueries.markAsPendingDeletion(noteId)
              noteQueries.updateSyncState(ids = listOf(noteId), syncState = IN_FLIGHT)
              noteQueries.deleteNote(noteId)
            }
          }
        }
      }
    }

    if (dbOperations.isNotEmpty()) {
      noteQueries.transaction {
        dbOperations.forEach { it.run() }
      }
    }

    val savedNotes = noteQueries.allNotes().executeAsList()
    register.pruneStaleRecords(savedNotes)
    if (git.isStagingAreaDirty()) {
      git.commitAll(
          message = "Update file name records",
          author = gitAuthor,
          timestamp = UtcTimestamp(clock)
      )
    }
  }

  private fun List<GitTreeDiff.Change>.filterNoteChanges() = filter { diff ->
    val path = diff.path
    when {
      !path.endsWith(".md") -> false                                        // Not a markdown note.
      path.startsWith(".press/") -> false                                   // Meta-files, ignore.
      path.contains("/") -> {
        when {
          path.startsWith("archived/") && !path.hasMultipleOf('/') -> true  // Archived note.
          else -> error("Folders aren't supported yet: '$path'")
        }
      }
      else -> true
    }
  }

  private fun GitScope.push(pullResult: PullResult, commitResult: CommitResult) {
    check(git.currentBranch().name == remote!!.defaultBranch) { "Not on the default branch" }
    check(!git.isStagingAreaDirty()) { "Expected staging area to be clean before pushing" }

    return if (commitResult == Skipped) {
      noteQueries.swapSyncStates(old = listOf(IN_FLIGHT), new = SYNCED)
      log("\nNothing to push.")

    } else when (git.push()) {
      is Success, is AlreadyUpToDate -> {
        noteQueries.swapSyncStates(old = listOf(IN_FLIGHT), new = SYNCED)
        log("\nChanges pushed")
      }
      // Merge conflicts can only be avoided if we always the latest version of upstream.
      // If push fails, discard any new commits. They'll be recreated on the next sync.
      is Failure -> {
        git.hardResetTo(pullResult.headAfter)
        noteQueries.swapSyncStates(old = listOf(IN_FLIGHT), new = PENDING)
        log("\nCouldn't push. Reverted to ${git.headCommit()}")
      }
    }
  }

  private fun log(message: String) = loggers.log(message)
}

@Suppress("FunctionName")
fun UtcTimestamp(time: DateTime): UtcTimestamp {
  return UtcTimestamp(time.unixMillisLong)
}

@Suppress("FunctionName")
fun UtcTimestamp(clock: Clock): UtcTimestamp {
  return UtcTimestamp(clock.nowUtc())
}

private val GitCommit.dateTime: DateTime
  get() = DateTime.fromUnix(utcTimestamp.millis)

private fun GitTreeDiff.flattenToString(): String {
  return joinToString(prefix = " • ", separator = "\n • ", postfix = "\n")
}
