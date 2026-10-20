#include <jni.h>
#include <cstdio>
#include <cstring>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixfmt.h>
#include <libswscale/swscale.h>
}

namespace {

void apply_decode_threads(AVCodecContext *codec_context, int threads) {
    if (codec_context == nullptr) {
        return;
    }
    // 帧线程会按线程数额外保留解码帧。单线程时关掉帧线程和重排缓冲，少留参考帧。
    if (threads <= 1) {
        codec_context->thread_count = 1;
        codec_context->thread_type = 0;
        codec_context->flags |= AV_CODEC_FLAG_LOW_DELAY;
        return;
    }
    codec_context->thread_count = threads;
    codec_context->thread_type = FF_THREAD_FRAME;
}

bool open_input_limited(AVFormatContext **format_context, const char *path) {
    AVDictionary *options = nullptr;
    // 默认探测会按约 5MB 预读。mp4 的封装头很小，把探测缓冲收短。
    av_dict_set(&options, "probesize", "65536", 0);
    av_dict_set(&options, "analyzeduration", "0", 0);
    av_dict_set(&options, "fflags", "nobuffer", 0);
    int opened = avformat_open_input(format_context, path, nullptr, &options);
    av_dict_free(&options);
    if (opened < 0) {
        if (*format_context != nullptr) {
            avformat_close_input(format_context);
        }
        opened = avformat_open_input(format_context, path, nullptr, nullptr);
        if (opened < 0) {
            return false;
        }
    }
    if (*format_context != nullptr) {
        (*format_context)->flags |= AVFMT_FLAG_NOBUFFER;
        (*format_context)->probesize = 64 * 1024;
        (*format_context)->max_analyze_duration = 0;
    }
    return true;
}

bool is_cancelled(JNIEnv *env, jobject session, jmethodID is_cancelled_method) {
    if (session == nullptr || is_cancelled_method == nullptr) {
        return false;
    }
    return env->CallBooleanMethod(session, is_cancelled_method) == JNI_TRUE;
}

void copy_plane(FILE *out, const uint8_t *src, int line_size, int width, int height) {
    for (int row = 0; row < height; ++row) {
        fwrite(src + row * line_size, 1, width, out);
    }
}

bool write_yuv420p(FILE *out, const AVFrame *frame) {
    if (frame == nullptr || out == nullptr) {
        return false;
    }

    const int width = frame->width;
    const int height = frame->height;
    copy_plane(out, frame->data[0], frame->linesize[0], width, height);
    copy_plane(out, frame->data[1], frame->linesize[1], width / 2, height / 2);
    copy_plane(out, frame->data[2], frame->linesize[2], width / 2, height / 2);
    return ferror(out) == 0;
}

void copy_plane_packed(uint8_t *&dst, const uint8_t *src, int line_size, int width, int height) {
    if (line_size == width) {
        const size_t bytes = static_cast<size_t>(width) * static_cast<size_t>(height);
        memcpy(dst, src, bytes);
        dst += bytes;
        return;
    }
    for (int row = 0; row < height; ++row) {
        memcpy(dst, src + static_cast<size_t>(row) * static_cast<size_t>(line_size), width);
        dst += width;
    }
}

bool copy_yuv420p_packed(uint8_t *dst, int dst_size, const AVFrame *frame) {
    if (frame == nullptr || dst == nullptr || frame->width <= 0 || frame->height <= 0) {
        return false;
    }
    const int needed = frame->width * frame->height * 3 / 2;
    if (dst_size < needed) {
        return false;
    }
    uint8_t *cursor = dst;
    copy_plane_packed(cursor, frame->data[0], frame->linesize[0], frame->width, frame->height);
    copy_plane_packed(cursor, frame->data[1], frame->linesize[1], frame->width / 2, frame->height / 2);
    copy_plane_packed(cursor, frame->data[2], frame->linesize[2], frame->width / 2, frame->height / 2);
    return true;
}

bool ensure_yuv_buffer(AVFrame *yuv_frame) {
    if (yuv_frame == nullptr) {
        return false;
    }
    if (yuv_frame->data[0] != nullptr) {
        return true;
    }
    return av_frame_get_buffer(yuv_frame, 32) == 0;
}

bool frame_matches_output(const AVFrame *frame, int output_width, int output_height) {
    return frame != nullptr &&
           frame->format == AV_PIX_FMT_YUV420P &&
           frame->width == output_width &&
           frame->height == output_height;
}

bool convert_and_write_yuv420p(
        FILE *output,
        SwsContext *sws_context,
        const AVFrame *frame,
        AVFrame *yuv_frame,
        int input_height,
        int output_width,
        int output_height) {
    if (frame_matches_output(frame, output_width, output_height)) {
        return write_yuv420p(output, frame);
    }
    if (!ensure_yuv_buffer(yuv_frame)) {
        return false;
    }

    sws_scale(
            sws_context,
            frame->data,
            frame->linesize,
            0,
            input_height,
            yuv_frame->data,
            yuv_frame->linesize);
    return write_yuv420p(output, yuv_frame);
}

bool deliver_yuv420p_frame(
        JNIEnv *env,
        jobject callback,
        jmethodID acquire_method,
        jmethodID on_frame_method,
        jmethodID release_method,
        SwsContext *sws_context,
        const AVFrame *frame,
        AVFrame *yuv_frame,
        int input_height,
        int output_width,
        int output_height) {
    if (callback == nullptr || acquire_method == nullptr || on_frame_method == nullptr) {
        return false;
    }

    const AVFrame *output_frame = frame;
    if (!frame_matches_output(frame, output_width, output_height)) {
        if (sws_context == nullptr || !ensure_yuv_buffer(yuv_frame)) {
            return false;
        }
        sws_scale(
                sws_context,
                frame->data,
                frame->linesize,
                0,
                input_height,
                yuv_frame->data,
                yuv_frame->linesize);
        output_frame = yuv_frame;
    }

    const int frame_size = output_frame->width * output_frame->height * 3 / 2;
    jobject buffer = env->CallObjectMethod(callback, acquire_method, frame_size);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    if (buffer == nullptr) {
        return false;
    }

    void *address = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity < frame_size ||
        !copy_yuv420p_packed(static_cast<uint8_t *>(address), static_cast<int>(capacity), output_frame)) {
        if (release_method != nullptr) {
            env->CallVoidMethod(callback, release_method, buffer);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(buffer);
        return false;
    }

    jboolean keep_running = env->CallBooleanMethod(callback, on_frame_method, buffer, frame_size);
    env->DeleteLocalRef(buffer);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return keep_running == JNI_TRUE;
}

int decode_to_raw_video(
        JNIEnv *env,
        const char *input_path,
        const char *output_path,
        jobject session,
        int threads,
        int output_width,
        int output_height) {
    AVFormatContext *format_context = nullptr;
    AVCodecContext *codec_context = nullptr;
    const AVCodec *codec = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *frame = nullptr;
    AVFrame *yuv_frame = nullptr;
    SwsContext *sws_context = nullptr;
    FILE *output = nullptr;
    int video_stream_index = -1;
    int result = 1;
    jmethodID is_cancelled_method = nullptr;

    if (session != nullptr) {
        jclass session_class = env->GetObjectClass(session);
        is_cancelled_method = env->GetMethodID(session_class, "isCancelled", "()Z");
        if (is_cancelled_method == nullptr) {
            env->ExceptionClear();
        }
    }

    if (!open_input_limited(&format_context, input_path)) {
        goto cleanup;
    }

    if (avformat_find_stream_info(format_context, nullptr) < 0) {
        goto cleanup;
    }

    for (unsigned int i = 0; i < format_context->nb_streams; ++i) {
        if (format_context->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            video_stream_index = static_cast<int>(i);
            break;
        }
    }
    if (video_stream_index < 0) {
        goto cleanup;
    }

    codec = avcodec_find_decoder(format_context->streams[video_stream_index]->codecpar->codec_id);
    if (codec == nullptr) {
        goto cleanup;
    }

    codec_context = avcodec_alloc_context3(codec);
    if (codec_context == nullptr) {
        goto cleanup;
    }

    if (avcodec_parameters_to_context(codec_context, format_context->streams[video_stream_index]->codecpar) < 0) {
        goto cleanup;
    }

    apply_decode_threads(codec_context, threads);
    codec_context->skip_loop_filter = AVDISCARD_ALL;
    codec_context->flags2 |= AV_CODEC_FLAG2_FAST;

    if (avcodec_open2(codec_context, codec, nullptr) < 0) {
        goto cleanup;
    }

    if (output_width <= 0) {
        output_width = codec_context->width;
    }
    if (output_height <= 0) {
        output_height = codec_context->height;
    }

    output = fopen(output_path, "wb");
    if (output == nullptr) {
        goto cleanup;
    }

    packet = av_packet_alloc();
    frame = av_frame_alloc();
    yuv_frame = av_frame_alloc();
    if (packet == nullptr || frame == nullptr || yuv_frame == nullptr) {
        goto cleanup;
    }

    yuv_frame->format = AV_PIX_FMT_YUV420P;
    yuv_frame->width = output_width;
    yuv_frame->height = output_height;

    sws_context = sws_getContext(
            codec_context->width,
            codec_context->height,
            codec_context->pix_fmt,
            output_width,
            output_height,
            AV_PIX_FMT_YUV420P,
            SWS_FAST_BILINEAR,
            nullptr,
            nullptr,
            nullptr);
    if (sws_context == nullptr) {
        goto cleanup;
    }

    while (!is_cancelled(env, session, is_cancelled_method) && av_read_frame(format_context, packet) >= 0) {
        if (packet->stream_index == video_stream_index) {
            if (avcodec_send_packet(codec_context, packet) == 0) {
                while (!is_cancelled(env, session, is_cancelled_method)) {
                    int receive_result = avcodec_receive_frame(codec_context, frame);
                    if (receive_result == AVERROR(EAGAIN) || receive_result == AVERROR_EOF) {
                        break;
                    }
                    if (receive_result < 0) {
                        goto cleanup;
                    }

                    if (!convert_and_write_yuv420p(
                            output,
                            sws_context,
                            frame,
                            yuv_frame,
                            codec_context->height,
                            output_width,
                            output_height)) {
                        goto cleanup;
                    }
                    av_frame_unref(frame);
                }
            }
        }
        av_packet_unref(packet);
    }

    avcodec_send_packet(codec_context, nullptr);
    while (!is_cancelled(env, session, is_cancelled_method)) {
        int receive_result = avcodec_receive_frame(codec_context, frame);
        if (receive_result == AVERROR(EAGAIN) || receive_result == AVERROR_EOF) {
            break;
        }
        if (receive_result < 0) {
            goto cleanup;
        }
        if (!convert_and_write_yuv420p(
                output,
                sws_context,
                frame,
                yuv_frame,
                codec_context->height,
                output_width,
                output_height)) {
            goto cleanup;
        }
        av_frame_unref(frame);
    }

    result = is_cancelled(env, session, is_cancelled_method) ? 255 : 0;

cleanup:
    if (output != nullptr) {
        fclose(output);
    }
    if (sws_context != nullptr) {
        sws_freeContext(sws_context);
    }
    if (yuv_frame != nullptr) {
        av_frame_free(&yuv_frame);
    }
    if (frame != nullptr) {
        av_frame_free(&frame);
    }
    if (packet != nullptr) {
        av_packet_free(&packet);
    }
    if (codec_context != nullptr) {
        avcodec_free_context(&codec_context);
    }
    if (format_context != nullptr) {
        avformat_close_input(&format_context);
    }
    return result;
}

int decode_to_frames(
        JNIEnv *env,
        const char *input_path,
        jobject session,
        int threads,
        int output_width,
        int output_height,
        jobject callback) {
    AVFormatContext *format_context = nullptr;
    AVCodecContext *codec_context = nullptr;
    const AVCodec *codec = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *frame = nullptr;
    AVFrame *yuv_frame = nullptr;
    SwsContext *sws_context = nullptr;
    int video_stream_index = -1;
    int result = 1;
    bool need_convert = false;
    jmethodID is_cancelled_method = nullptr;
    jmethodID acquire_method = nullptr;
    jmethodID on_frame_method = nullptr;
    jmethodID release_method = nullptr;

    if (session != nullptr) {
        jclass session_class = env->GetObjectClass(session);
        is_cancelled_method = env->GetMethodID(session_class, "isCancelled", "()Z");
        if (is_cancelled_method == nullptr) {
            env->ExceptionClear();
        }
    }
    if (callback != nullptr) {
        jclass callback_class = env->GetObjectClass(callback);
        acquire_method = env->GetMethodID(callback_class, "acquireFrame", "(I)Ljava/nio/ByteBuffer;");
        on_frame_method = env->GetMethodID(callback_class, "onFrame", "(Ljava/nio/ByteBuffer;I)Z");
        release_method = env->GetMethodID(callback_class, "releaseFrame", "(Ljava/nio/ByteBuffer;)V");
        if (acquire_method == nullptr || on_frame_method == nullptr || release_method == nullptr) {
            env->ExceptionClear();
            goto cleanup;
        }
    }

    if (!open_input_limited(&format_context, input_path)) {
        goto cleanup;
    }
    if (avformat_find_stream_info(format_context, nullptr) < 0) {
        goto cleanup;
    }
    for (unsigned int i = 0; i < format_context->nb_streams; ++i) {
        if (format_context->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            video_stream_index = static_cast<int>(i);
            break;
        }
    }
    if (video_stream_index < 0) {
        goto cleanup;
    }
    codec = avcodec_find_decoder(format_context->streams[video_stream_index]->codecpar->codec_id);
    if (codec == nullptr) {
        goto cleanup;
    }
    codec_context = avcodec_alloc_context3(codec);
    if (codec_context == nullptr) {
        goto cleanup;
    }
    if (avcodec_parameters_to_context(codec_context, format_context->streams[video_stream_index]->codecpar) < 0) {
        goto cleanup;
    }
    apply_decode_threads(codec_context, threads);
    codec_context->skip_loop_filter = AVDISCARD_ALL;
    codec_context->flags2 |= AV_CODEC_FLAG2_FAST;
    if (avcodec_open2(codec_context, codec, nullptr) < 0) {
        goto cleanup;
    }
    if (output_width <= 0) {
        output_width = codec_context->width;
    }
    if (output_height <= 0) {
        output_height = codec_context->height;
    }

    packet = av_packet_alloc();
    frame = av_frame_alloc();
    yuv_frame = av_frame_alloc();
    if (packet == nullptr || frame == nullptr || yuv_frame == nullptr) {
        goto cleanup;
    }
    yuv_frame->format = AV_PIX_FMT_YUV420P;
    yuv_frame->width = output_width;
    yuv_frame->height = output_height;
    need_convert =
            codec_context->pix_fmt != AV_PIX_FMT_YUV420P ||
            codec_context->width != output_width ||
            codec_context->height != output_height;
    if (need_convert) {
        sws_context = sws_getContext(
                codec_context->width,
                codec_context->height,
                codec_context->pix_fmt,
                output_width,
                output_height,
                AV_PIX_FMT_YUV420P,
                SWS_FAST_BILINEAR,
                nullptr,
                nullptr,
                nullptr);
        if (sws_context == nullptr) {
            goto cleanup;
        }
    }

    while (!is_cancelled(env, session, is_cancelled_method) && av_read_frame(format_context, packet) >= 0) {
        if (packet->stream_index == video_stream_index && avcodec_send_packet(codec_context, packet) == 0) {
            while (!is_cancelled(env, session, is_cancelled_method)) {
                int receive_result = avcodec_receive_frame(codec_context, frame);
                if (receive_result == AVERROR(EAGAIN) || receive_result == AVERROR_EOF) {
                    break;
                }
                if (receive_result < 0) {
                    goto cleanup;
                }
                if (!deliver_yuv420p_frame(
                        env,
                        callback,
                        acquire_method,
                        on_frame_method,
                        release_method,
                        sws_context,
                        frame,
                        yuv_frame,
                        codec_context->height,
                        output_width,
                        output_height)) {
                    result = is_cancelled(env, session, is_cancelled_method) ? 255 : 0;
                    goto cleanup;
                }
                av_frame_unref(frame);
            }
        }
        av_packet_unref(packet);
    }

    avcodec_send_packet(codec_context, nullptr);
    while (!is_cancelled(env, session, is_cancelled_method)) {
        int receive_result = avcodec_receive_frame(codec_context, frame);
        if (receive_result == AVERROR(EAGAIN) || receive_result == AVERROR_EOF) {
            break;
        }
        if (receive_result < 0) {
            goto cleanup;
        }
        if (!deliver_yuv420p_frame(
                env,
                callback,
                acquire_method,
                on_frame_method,
                release_method,
                sws_context,
                frame,
                yuv_frame,
                codec_context->height,
                output_width,
                output_height)) {
            result = is_cancelled(env, session, is_cancelled_method) ? 255 : 0;
            goto cleanup;
        }
        av_frame_unref(frame);
    }
    result = is_cancelled(env, session, is_cancelled_method) ? 255 : 0;

cleanup:
    if (sws_context != nullptr) {
        sws_freeContext(sws_context);
    }
    if (yuv_frame != nullptr) {
        av_frame_free(&yuv_frame);
    }
    if (frame != nullptr) {
        av_frame_free(&frame);
    }
    if (packet != nullptr) {
        av_packet_free(&packet);
    }
    if (codec_context != nullptr) {
        avcodec_free_context(&codec_context);
    }
    if (format_context != nullptr) {
        avformat_close_input(&format_context);
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_arthenica_ffmpegkit_LiteFFmpegNative_decodeToRawVideo(
        JNIEnv *env,
        jobject,
        jstring input_path,
        jstring output_path,
        jobject session,
        jint threads,
        jint output_width,
        jint output_height) {
    const char *input = env->GetStringUTFChars(input_path, nullptr);
    const char *output = env->GetStringUTFChars(output_path, nullptr);
    int result = decode_to_raw_video(env, input, output, session, threads, output_width, output_height);
    env->ReleaseStringUTFChars(input_path, input);
    env->ReleaseStringUTFChars(output_path, output);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arthenica_ffmpegkit_LiteFFmpegNative_decodeToFrames(
        JNIEnv *env,
        jobject,
        jstring input_path,
        jobject session,
        jint threads,
        jint output_width,
        jint output_height,
        jobject callback) {
    const char *input = env->GetStringUTFChars(input_path, nullptr);
    int result = decode_to_frames(env, input, session, threads, output_width, output_height, callback);
    env->ReleaseStringUTFChars(input_path, input);
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_arthenica_ffmpegkit_LiteFFmpegNative_probeVideoSize(
        JNIEnv *env,
        jobject,
        jstring input_path) {
    const char *input = env->GetStringUTFChars(input_path, nullptr);
    AVFormatContext *format_context = nullptr;
    int width = 0;
    int height = 0;

    if (open_input_limited(&format_context, input) &&
        avformat_find_stream_info(format_context, nullptr) == 0) {
        for (unsigned int i = 0; i < format_context->nb_streams; ++i) {
            AVCodecParameters *parameters = format_context->streams[i]->codecpar;
            if (parameters->codec_type == AVMEDIA_TYPE_VIDEO) {
                width = parameters->width;
                height = parameters->height;
                break;
            }
        }
    }

    if (format_context != nullptr) {
        avformat_close_input(&format_context);
    }
    env->ReleaseStringUTFChars(input_path, input);

    if (width <= 0 || height <= 0) {
        return nullptr;
    }

    jint values[2] = {width, height};
    jintArray result = env->NewIntArray(2);
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}
