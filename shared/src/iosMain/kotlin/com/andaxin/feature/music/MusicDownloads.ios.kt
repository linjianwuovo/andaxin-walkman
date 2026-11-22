@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.andaxin.feature.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import platform.Foundation.*
import platform.darwin.NSObject

object IosMusicDownloads {
    var songs by mutableStateOf(MusicStore.loadDownloadedSongs())
        private set
    val progress = mutableStateMapOf<Long, Float>()
    val activeSongs = mutableStateMapOf<Long, MusicSong>()
    private val cancelledTasks = mutableSetOf<String>()
    private val completedDuringRestore = mutableSetOf<String>()
    private val pending = mutableMapOf<Long, Pair<MusicSong, String>>()
    private var restoreStarted = false
    private var restoring = false
    var message by mutableStateOf<String?>(null)
        private set
    private val tasks = mutableMapOf<Long, NSURLSessionDownloadTask>()
    private val fileManager = NSFileManager.defaultManager
    private val directory: String = (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String) + "/Music"
    private val delegate = DownloadDelegate()
    private var backgroundCompletion: (() -> Unit)? = null
    private val session: NSURLSession by lazy {
        val configuration = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier("com.andaxin.music.downloads")
        configuration.sessionSendsLaunchEvents = true
        configuration.discretionary = false
        NSURLSession.sessionWithConfiguration(configuration, delegate, NSOperationQueue.mainQueue)
    }

    fun restore() {
        if (restoreStarted) return
        restoreStarted = true
        restoring = true
        fileManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
        songs = songs.filter { localUrl(it.id) != null }
        MusicStore.saveDownloadedSongs(songs)
        session.getAllTasksWithCompletionHandler { restored ->
            NSOperationQueue.mainQueue.addOperationWithBlock {
                restored.orEmpty().forEach { task ->
                    val download = task as? NSURLSessionDownloadTask ?: return@forEach
                    if (download.taskIdentifier.toString() in completedDuringRestore) return@forEach
                    val song = decodeQueue(download.taskDescription.orEmpty()).firstOrNull() ?: return@forEach
                    if (song.id in tasks) return@forEach
                    tasks[song.id] = download
                    activeSongs[song.id] = song
                    update(download, download.countOfBytesReceived, download.countOfBytesExpectedToReceive)
                }
                restoring = false
                completedDuringRestore.clear()
                val queued = pending.values.toList()
                pending.clear()
                queued.forEach { (song, url) -> enqueue(song, url) }
            }
        }
    }

    fun handleBackgroundEvents(completion: () -> Unit) {
        backgroundCompletion = completion
        restore()
    }

    internal fun finishBackgroundEvents() {
        val completion = backgroundCompletion
        backgroundCompletion = null
        completion?.invoke()
    }

    internal fun enqueue(song: MusicSong, url: String) {
        if (restoring) {
            pending[song.id] = song to url
            return
        }
        if (song.id in tasks || localUrl(song.id) != null) return
        val source = NSURL.URLWithString(url)
        if (source == null || source.scheme !in listOf("http", "https")) {
            message = "没有可下载的音频地址"
            return
        }
        fileManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
        val task = session.downloadTaskWithURL(source)
        task.taskDescription = encodeQueue(listOf(song))
        tasks[song.id] = task
        activeSongs[song.id] = song
        progress[song.id] = 0f
        message = null
        task.resume()
    }

    fun cancel(songId: Long) {
        pending.remove(songId)
        tasks.remove(songId)?.let { task ->
            cancelledTasks.add(task.taskIdentifier.toString())
            task.cancel()
        }
        progress.remove(songId)
        activeSongs.remove(songId)
    }

    fun remove(songId: Long) {
        val fileName = MusicStore.loadDownloadPaths()[songId]
        if (fileName != null && fileManager.fileExistsAtPath("$directory/$fileName") && !fileManager.removeItemAtPath("$directory/$fileName", error = null)) {
            message = "文件暂时无法删除"
            return
        }
        songs = songs.filterNot { it.id == songId }
        MusicStore.saveDownloadedSongs(songs)
        MusicStore.saveDownloadPaths(MusicStore.loadDownloadPaths() - songId)
    }

    fun localUrl(songId: Long): String? {
        val name = MusicStore.loadDownloadPaths()[songId] ?: return null
        if (name.contains('/') || name.contains('\\')) return null
        val path = "$directory/$name"
        return if (fileManager.fileExistsAtPath(path)) NSURL.fileURLWithPath(path).absoluteString else null
    }

    internal fun update(task: NSURLSessionDownloadTask, written: Long, expected: Long) {
        val song = decodeQueue(task.taskDescription.orEmpty()).firstOrNull() ?: return
        if (tasks[song.id]?.taskIdentifier != task.taskIdentifier) return
        progress[song.id] = if (expected > 0L) (written.toFloat() / expected).coerceIn(0f, 1f) else 0f
    }

    internal fun finished(task: NSURLSessionDownloadTask, location: NSURL) {
        if (task.taskIdentifier.toString() in cancelledTasks) return
        val song = decodeQueue(task.taskDescription.orEmpty()).firstOrNull() ?: return
        val response = task.response as? NSHTTPURLResponse
        val mime = response?.MIMEType.orEmpty().lowercase()
        if (response == null || response.statusCode !in 200L..299L || mime.startsWith("text/") || mime.contains("json")) {
            message = "${song.name} 下载失败：服务未返回音频文件"
            return
        }
        val ext = response.URL?.pathExtension?.lowercase()?.takeIf { it in setOf("mp3", "m4a", "aac", "flac", "wav", "mp4", "ogg") }
            ?: when { mime.contains("mpeg") -> "mp3"; mime.contains("mp4") -> "m4a"; mime.contains("flac") -> "flac"; else -> "audio" }
        val fileName = "${song.id}.$ext"
        val destination = "$directory/$fileName"
        val sourcePath = location.path ?: return
        val bytes = (fileManager.attributesOfItemAtPath(sourcePath, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
        if (bytes <= 0L || !fileManager.moveItemAtPath(sourcePath, toPath = destination, error = null)) {
            message = "${song.name} 无法保存，请检查可用空间"
            return
        }
        MusicStore.saveDownloadPaths(MusicStore.loadDownloadPaths() + (song.id to fileName))
        songs = listOf(song) + songs.filterNot { it.id == song.id }
        MusicStore.saveDownloadedSongs(songs)
        message = "已保存 ${song.name}，可离线播放"
    }

    internal fun completed(task: NSURLSessionTask, error: NSError?) {
        val song = decodeQueue(task.taskDescription.orEmpty()).firstOrNull() ?: return
        val identifier = task.taskIdentifier.toString()
        if (restoring) completedDuringRestore.add(identifier)
        val cancelled = cancelledTasks.remove(identifier)
        val current = tasks[song.id]
        if (current != null && current.taskIdentifier != task.taskIdentifier) return
        tasks.remove(song.id)
        progress.remove(song.id)
        activeSongs.remove(song.id)
        if (!cancelled && error != null && error.code != -999L) message = "${song.name} 下载失败：${error.localizedDescription}"
    }
}

private class DownloadDelegate : NSObject(), NSURLSessionDownloadDelegateProtocol {
    override fun URLSession(session: NSURLSession, downloadTask: NSURLSessionDownloadTask, didFinishDownloadingToURL: NSURL) {
        IosMusicDownloads.finished(downloadTask, didFinishDownloadingToURL)
    }

    override fun URLSession(session: NSURLSession, downloadTask: NSURLSessionDownloadTask, didWriteData: Long, totalBytesWritten: Long, totalBytesExpectedToWrite: Long) {
        IosMusicDownloads.update(downloadTask, totalBytesWritten, totalBytesExpectedToWrite)
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        IosMusicDownloads.completed(task, didCompleteWithError)
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        IosMusicDownloads.finishBackgroundEvents()
    }
}
