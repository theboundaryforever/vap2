package com.arthenica.ffmpegkit

object FFprobeKit {

    fun getMediaInformation(path: String): FFprobeSession {
        val size = try {
            LiteFFmpegNative.probeVideoSize(path)
        } catch (e: Throwable) {
            null
        }

        val information = size?.takeIf { it.size >= 2 }?.let {
            MediaInformation(
                streams = listOf(
                    StreamInformation(
                        type = "video",
                        width = it[0],
                        height = it[1]
                    )
                )
            )
        }

        return FFprobeSession(information)
    }
}

class FFprobeSession internal constructor(
    val mediaInformation: MediaInformation?
)

class MediaInformation internal constructor(
    val streams: List<StreamInformation>
)

class StreamInformation internal constructor(
    val type: String,
    val width: Int,
    val height: Int
)
