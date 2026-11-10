/**
 * taglib_jni.cpp
 *
 * Thin JNI bridge between Kotlin and TagLib. This file handles both reading
 * and writing audio metadata. The only currency it deals in is raw POSIX file
 * descriptors — so it works with both regular files and SAF content:// URIs
 * without needing to know anything about the actual path on disk.
 *
 * @author Hamza417
 */

#include <jni.h>
#include <unistd.h>
#include <android/log.h>

#include <taglib/fileref.h>
#include <taglib/tfilestream.h>
#include <taglib/tag.h>
#include <taglib/audioproperties.h>
#include <taglib/tpropertymap.h>
#include <taglib/tvariant.h>

#include <string>
#include "taglib/tstring.h"
#include "taglib/flacproperties.h"
#include "taglib/wavproperties.h"
#include "taglib/aiffproperties.h"
#include "taglib/apeproperties.h"

#define LOG_TAG "TagLibJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// RAII wrapper for duplicated file descriptors. TagLib::FileStream(int fd, bool openReadOnly)
// sets d->closeFile = false internally and NEVER closes a caller-supplied file descriptor,
// so we must close dupFd ourselves after TagLib::FileStream is destroyed.
struct ScopedFd {
    int fd;
    explicit ScopedFd(int f) : fd(f) {}
    ~ScopedFd() {
        if (fd >= 0) {
            close(fd);
        }
    }
    ScopedFd(const ScopedFd &) = delete;
    ScopedFd &operator=(const ScopedFd &) = delete;
};

static jclass g_metaClass = nullptr;
static jmethodID g_metaCtor = nullptr;

static jstring toJString(JNIEnv *env, const TagLib::String &str) {
    if (str.isEmpty()) return nullptr;
    std::string utf8 = str.to8Bit(true /* utf8 */);
    jstring result = env->NewStringUTF(utf8.c_str());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

static jstring getProperty(JNIEnv *env, const TagLib::PropertyMap &props, const char *key) {
    TagLib::String k(key);
    auto it = props.find(k);
    if (it == props.end() || it->second.isEmpty()) return nullptr;
    return toJString(env, it->second.front());
}

static TagLib::String fromJString(JNIEnv *env, jstring js) {
    if (!js) return {};
    const char *chars = env->GetStringUTFChars(js, nullptr);
    if (!chars) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return {};
    }
    TagLib::String s(chars, TagLib::String::UTF8);
    env->ReleaseStringUTFChars(js, chars);
    return s;
}

static void applyProperty(JNIEnv *env, TagLib::PropertyMap &props,
                          const char *key, jstring js) {
    if (!js) return;
    TagLib::String value = fromJString(env, js);
    if (value.isEmpty()) {
        props.erase(TagLib::String(key));
    } else {
        TagLib::StringList list;
        list.append(value);
        props[TagLib::String(key)] = list;
    }
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * /*vm*/, void * /*reserved*/) {
    return JNI_VERSION_1_6;
}

static bool ensureMetaClassCached(JNIEnv *env) {
    if (g_metaClass && g_metaCtor) return true;

    jclass localClass = env->FindClass("app/simple/felicity/repository/metadata/TagLibMetadata");
    if (!localClass) {
        LOGE("Cannot find TagLibMetadata class — check the package name");
        return false;
    }

    g_metaClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);

    g_metaCtor = env->GetMethodID(g_metaClass, "<init>",
                                  "("
                                  "Ljava/lang/String;"   // title
                                  "Ljava/lang/String;"   // artist
                                  "Ljava/lang/String;"   // album
                                  "Ljava/lang/String;"   // genre
                                  "Ljava/lang/String;"   // year
                                  "Ljava/lang/String;"   // comment
                                  "Ljava/lang/String;"   // albumArtist
                                  "Ljava/lang/String;"   // composer
                                  "Ljava/lang/String;"   // lyricist
                                  "Ljava/lang/String;"   // discNumber
                                  "Ljava/lang/String;"   // trackNumber
                                  "Ljava/lang/String;"   // numTracks
                                  "Ljava/lang/String;"   // compilation
                                  "JJJJ"                 // duration, bitrate, sampleRate, bitsPerSample
                                  "Ljava/lang/String;"   // replayGainTrackGain
                                  "Ljava/lang/String;"   // replayGainTrackPeak
                                  "Ljava/lang/String;"   // replayGainAlbumGain
                                  "Ljava/lang/String;"   // replayGainAlbumPeak
                                  ")V");

    if (!g_metaCtor) {
        LOGE("Cannot find TagLibMetadata constructor — signature mismatch");
        return false;
    }

    return true;
}

JNIEXPORT jobject JNICALL
Java_app_simple_felicity_repository_metadata_TagLibBridge_nativeLoadFromFd(
        JNIEnv *env, jobject thiz, jint fd) {

    int dupFd = dup(static_cast<int>(fd));
    if (dupFd < 0) {
        LOGE("dup() failed for fd=%d — cannot read tags", fd);
        return nullptr;
    }
    ScopedFd fdGuard(dupFd);

    TagLib::FileStream stream(dupFd, true /* openReadOnly */);

    TagLib::FileRef fileRef(
            static_cast<TagLib::IOStream *>(&stream),
            true,
            TagLib::AudioProperties::ReadStyle::Fast
    );
    if (fileRef.isNull()) {
        LOGE("FileRef is null for fd=%d — unsupported format or corrupt file", fd);
        return nullptr;
    }

    TagLib::Tag *tag = fileRef.tag();
    TagLib::AudioProperties *ap = fileRef.audioProperties();
    TagLib::PropertyMap props;
    if (tag) props = tag->properties();

    jstring title = tag ? toJString(env, tag->title()) : nullptr;
    jstring artist = tag ? toJString(env, tag->artist()) : nullptr;
    jstring album = tag ? toJString(env, tag->album()) : nullptr;
    jstring genre = tag ? toJString(env, tag->genre()) : nullptr;
    jstring comment = tag ? toJString(env, tag->comment()) : nullptr;

    jstring year = nullptr;
    if (tag && tag->year() > 0) {
        std::string yearStr = std::to_string(tag->year());
        year = env->NewStringUTF(yearStr.c_str());
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            year = nullptr;
        }
    }

    jstring albumArtist = getProperty(env, props, "ALBUMARTIST");
    if (!albumArtist) albumArtist = getProperty(env, props, "ALBUM ARTIST");
    jstring composer = getProperty(env, props, "COMPOSER");
    jstring lyricist = getProperty(env, props, "LYRICIST");
    jstring discNumber = nullptr;
    {
        jstring rawDisc = getProperty(env, props, "DISCNUMBER");
        if (rawDisc) {
            const char *raw = env->GetStringUTFChars(rawDisc, nullptr);
            if (raw) {
                std::string rawStr(raw);
                env->ReleaseStringUTFChars(rawDisc, raw);
                auto slash = rawStr.find('/');
                if (slash != std::string::npos) {
                    std::string numPart = rawStr.substr(0, slash);
                    if (!numPart.empty()) discNumber = env->NewStringUTF(numPart.c_str());
                } else {
                    discNumber = rawDisc;
                }
            }
        }
    }
    jstring trackNumber = nullptr;
    jstring numTracks = nullptr;
    {
        jstring rawTrack = getProperty(env, props, "TRACKNUMBER");
        if (rawTrack) {
            const char *raw = env->GetStringUTFChars(rawTrack, nullptr);
            if (raw) {
                std::string rawStr(raw);
                env->ReleaseStringUTFChars(rawTrack, raw);
                auto slash = rawStr.find('/');
                if (slash != std::string::npos) {
                    std::string numPart = rawStr.substr(0, slash);
                    std::string totalPart = rawStr.substr(slash + 1);
                    if (!numPart.empty()) trackNumber = env->NewStringUTF(numPart.c_str());
                    if (!totalPart.empty()) numTracks = env->NewStringUTF(totalPart.c_str());
                } else {
                    trackNumber = rawTrack;
                }
            }
        }
        if (!numTracks) numTracks = getProperty(env, props, "TRACKTOTAL");
        if (!numTracks) numTracks = getProperty(env, props, "TOTALTRACKS");
    }
    jstring compilation = getProperty(env, props, "COMPILATION");

    jstring rgTrackGain = getProperty(env, props, "REPLAYGAIN_TRACK_GAIN");
    jstring rgTrackPeak = getProperty(env, props, "REPLAYGAIN_TRACK_PEAK");
    jstring rgAlbumGain = getProperty(env, props, "REPLAYGAIN_ALBUM_GAIN");
    jstring rgAlbumPeak = getProperty(env, props, "REPLAYGAIN_ALBUM_PEAK");

    jlong duration = ap ? static_cast<jlong>(ap->lengthInMilliseconds()) : 0L;
    jlong bitrate = ap ? static_cast<jlong>(ap->bitrate()) : 0L;
    jlong sampleRate = ap ? static_cast<jlong>(ap->sampleRate()) : 0L;
    jint channels = ap ? ap->channels() : 0;
    jlong bitsPerSample = 0;

    if (ap) {
        if (auto flacProps = dynamic_cast<TagLib::FLAC::Properties *>(ap)) {
            bitsPerSample = static_cast<jlong>(flacProps->bitsPerSample());
        } else if (auto wavProps = dynamic_cast<TagLib::RIFF::WAV::Properties *>(ap)) {
            bitsPerSample = static_cast<jlong>(wavProps->bitsPerSample());
        } else if (auto aiffProps = dynamic_cast<TagLib::RIFF::AIFF::Properties *>(ap)) {
            bitsPerSample = static_cast<jlong>(aiffProps->bitsPerSample());
        } else if (auto apeProps = dynamic_cast<TagLib::APE::Properties *>(ap)) {
            bitsPerSample = static_cast<jlong>(apeProps->bitsPerSample());
        } else if (sampleRate > 0 && channels > 0) {
            bitsPerSample =
                    ((bitrate * 1000) + (sampleRate * channels) / 2) / (sampleRate * channels);
            if (bitsPerSample > 0 && bitsPerSample < 16) {
                bitsPerSample = 16;
            }
        }
    }

    if (!ensureMetaClassCached(env)) return nullptr;

    return env->NewObject(
            g_metaClass, g_metaCtor,
            title, artist, album, genre, year, comment,
            albumArtist, composer, lyricist, discNumber, trackNumber, numTracks, compilation,
            duration, bitrate, sampleRate, bitsPerSample,
            rgTrackGain, rgTrackPeak, rgAlbumGain, rgAlbumPeak);
}

JNIEXPORT jboolean JNICALL
Java_app_simple_felicity_repository_metadata_TagLibBridge_nativeSaveToFd(
        JNIEnv *env, jobject thiz, jint fd,
        jstring title, jstring artist, jstring album, jstring albumArtist,
        jstring year, jstring trackNumber, jstring numTracks, jstring discNumber,
        jstring genre, jstring composer, jstring lyricist, jstring compilation,
        jstring comment, jstring lyrics,
        jstring replayGainTrackGain, jstring replayGainTrackPeak,
        jstring replayGainAlbumGain, jstring replayGainAlbumPeak) {

    int dupFd = dup(static_cast<int>(fd));
    if (dupFd < 0) {
        LOGE("dup() failed for fd=%d — cannot write tags", fd);
        return JNI_FALSE;
    }
    ScopedFd fdGuard(dupFd);

    TagLib::FileStream stream(dupFd, false /* openReadOnly */);

    TagLib::FileRef fileRef(&stream, false);
    if (fileRef.isNull()) {
        LOGE("FileRef is null for fd=%d — unsupported format or corrupt file", fd);
        return JNI_FALSE;
    }

    TagLib::Tag *tag = fileRef.tag();
    if (!tag) {
        LOGE("File has no writable tag for fd=%d", fd);
        return JNI_FALSE;
    }

    if (title) tag->setTitle(fromJString(env, title));
    if (artist) tag->setArtist(fromJString(env, artist));
    if (album) tag->setAlbum(fromJString(env, album));
    if (genre) tag->setGenre(fromJString(env, genre));
    if (comment) tag->setComment(fromJString(env, comment));

    if (year) {
        TagLib::String yearStr = fromJString(env, year);
        auto yearUint = static_cast<unsigned int>(std::strtoul(yearStr.toCString(), nullptr, 10));
        if (yearUint > 0) tag->setYear(yearUint);
    }

    TagLib::PropertyMap props = tag->properties();

    applyProperty(env, props, "ALBUMARTIST", albumArtist);
    applyProperty(env, props, "COMPOSER", composer);
    applyProperty(env, props, "LYRICIST", lyricist);
    applyProperty(env, props, "DISCNUMBER", discNumber);
    applyProperty(env, props, "TRACKNUMBER", trackNumber);
    applyProperty(env, props, "TRACKTOTAL", numTracks);
    applyProperty(env, props, "COMPILATION", compilation);
    applyProperty(env, props, "LYRICS", lyrics);

    applyProperty(env, props, "REPLAYGAIN_TRACK_GAIN", replayGainTrackGain);
    applyProperty(env, props, "REPLAYGAIN_TRACK_PEAK", replayGainTrackPeak);
    applyProperty(env, props, "REPLAYGAIN_ALBUM_GAIN", replayGainAlbumGain);
    applyProperty(env, props, "REPLAYGAIN_ALBUM_PEAK", replayGainAlbumPeak);

    tag->setProperties(props);

    if (!fileRef.save()) {
        LOGE("TagLib save() failed for fd=%d", fd);
        return JNI_FALSE;
    }

    LOGI("Tags saved successfully for fd=%d", fd);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_app_simple_felicity_repository_metadata_TagLibBridge_nativeSaveArtworkToFd(
        JNIEnv *env, jobject thiz, jint fd,
        jbyteArray artworkData, jstring mimeType) {

    int dupFd = dup(static_cast<int>(fd));
    if (dupFd < 0) {
        LOGE("dup() failed for fd=%d — cannot write artwork", fd);
        return JNI_FALSE;
    }
    ScopedFd fdGuard(dupFd);

    TagLib::FileStream stream(dupFd, false /* openReadOnly */);
    TagLib::FileRef fileRef(&stream, false);
    if (fileRef.isNull() || !fileRef.file()) {
        LOGE("FileRef is null for fd=%d — unsupported format", fd);
        return JNI_FALSE;
    }

    jsize dataLen = env->GetArrayLength(artworkData);
    jbyte *dataPtr = env->GetByteArrayElements(artworkData, nullptr);
    if (!dataPtr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGE("GetByteArrayElements returned null for fd=%d — out of memory?", fd);
        return JNI_FALSE;
    }
    TagLib::ByteVector imageData(reinterpret_cast<const char *>(dataPtr),
                                 static_cast<unsigned int>(dataLen));
    env->ReleaseByteArrayElements(artworkData, dataPtr, JNI_ABORT);

    TagLib::VariantMap pic;
    pic["data"] = TagLib::Variant(imageData);
    pic["mimeType"] = TagLib::Variant(fromJString(env, mimeType));
    pic["description"] = TagLib::Variant(TagLib::String());
    pic["pictureType"] = TagLib::Variant(3); // 3 = Front Cover

    TagLib::List<TagLib::VariantMap> pictures;
    pictures.append(pic);

    fileRef.file()->setComplexProperties("PICTURE", pictures);

    if (!fileRef.save()) {
        LOGE("TagLib save() failed while writing artwork for fd=%d", fd);
        return JNI_FALSE;
    }

    LOGI("Artwork saved successfully for fd=%d (%d bytes)", fd, dataLen);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_app_simple_felicity_repository_metadata_TagLibBridge_nativeExtractLyricsFromFd(
        JNIEnv *env, jobject thiz, jint fd) {

    int dupFd = dup(static_cast<int>(fd));
    if (dupFd < 0) {
        LOGE("dup() failed for fd=%d — cannot extract lyrics", fd);
        return nullptr;
    }
    ScopedFd fdGuard(dupFd);

    TagLib::FileStream stream(dupFd, true /* openReadOnly */);
    TagLib::FileRef fileRef(&stream, false);
    if (fileRef.isNull()) {
        LOGE("FileRef is null for fd=%d — unsupported format", fd);
        return nullptr;
    }

    TagLib::Tag *tag = fileRef.tag();
    if (!tag) return nullptr;

    TagLib::PropertyMap props = tag->properties();

    jstring lyrics = getProperty(env, props, "LYRICS");
    if (lyrics) return lyrics;

    return getProperty(env, props, "UNSYNCEDLYRICS");
}

JNIEXPORT jbyteArray JNICALL
Java_app_simple_felicity_repository_metadata_TagLibBridge_nativeExtractArtworkFromFd(
        JNIEnv *env, jobject thiz, jint fd) {

    int dupFd = dup(static_cast<int>(fd));
    if (dupFd < 0) {
        LOGE("dup() failed for fd=%d — cannot extract artwork", fd);
        return nullptr;
    }
    ScopedFd fdGuard(dupFd);

    TagLib::FileStream stream(dupFd, true /* openReadOnly */);
    TagLib::FileRef fileRef(&stream, false);
    if (fileRef.isNull() || !fileRef.file()) {
        LOGE("FileRef is null for fd=%d — unsupported format", fd);
        return nullptr;
    }

    auto pictures = fileRef.file()->complexProperties("PICTURE");
    if (pictures.isEmpty()) {
        return nullptr;
    }

    const auto &pic = pictures.front();
    auto dataIt = pic.find("data");
    if (dataIt == pic.end()) {
        return nullptr;
    }

    const TagLib::ByteVector &imageData = dataIt->second.value<TagLib::ByteVector>();
    if (imageData.isEmpty()) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(imageData.size()));
    if (!result) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGE("NewByteArray failed for fd=%d — out of memory?", fd);
        return nullptr;
    }

    env->SetByteArrayRegion(
            result, 0, static_cast<jsize>(imageData.size()),
            reinterpret_cast<const jbyte *>(imageData.data())
    );

    LOGI("Extracted %d bytes of artwork from fd=%d", static_cast<int>(imageData.size()), fd);
    return result;
}

} // extern "C"
