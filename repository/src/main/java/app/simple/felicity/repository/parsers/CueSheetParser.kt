package app.simple.felicity.repository.parsers

/**
 * A single track parsed out of a `.cue` sheet.
 *
 * [startMs] is always known (every `TRACK` block has an `INDEX 01`, and usually an
 * `INDEX 00` pre-gap too — [startMs] is always `INDEX 01`, the actual start of audible
 * playback, matching how every mainstream player treats cue sheets). [endMs] is `null`
 * for the last track in the sheet, since a cue sheet has no way to state where the last
 * track ends — that track simply plays to the physical end of [CueSheet.fileName].
 */
data class CueTrack(
        val number: Int,
        val title: String?,
        val performer: String?,
        val startMs: Long,
        val endMs: Long?
)

/**
 * A fully parsed `.cue` sheet: album-level metadata plus every track it splits the
 * referenced audio file into.
 *
 * @param fileName The value of the sheet's `FILE "..." <TYPE>` line — the audio file this
 *                 sheet describes, exactly as written in the sheet (relative, same-folder
 *                 reference in the overwhelming majority of real-world cue sheets). It is
 *                 the caller's job to resolve this against the `.cue` file's own directory;
 *                 [CueSheetParser] never touches the filesystem itself.
 */
data class CueSheet(
        val fileName: String,
        val albumTitle: String?,
        val albumPerformer: String?,
        val tracks: List<CueTrack>
)

/**
 * Parses the `.cue` sheet text format (EAC / CDRDAO-style, the de facto standard for
 * single-file "one big FLAC/APE/WAV + a .cue" album rips) into a [CueSheet].
 *
 * Deliberately scoped to exactly what a music player needs — track boundaries and basic
 * metadata — and ignores fields that only matter for actual CD burning (`FLAGS`, `PREGAP`,
 * `POSTGAP`, `CATALOG`, `ISRC`, `REM` comments other than a couple of common ripper tags).
 * Multi-`FILE` cue sheets (rare — a handful of sheets reference more than one audio file)
 * are intentionally unsupported: [parse] always associates every track with the single
 * `FILE` line already seen, which is correct for the overwhelming majority of sheets in
 * the wild and keeps this parser simple enough to trust without a test corpus.
 *
 * @author Hamza417
 */
object CueSheetParser {

    private const val TAG = "CueSheetParser"

    /**
     * @param text The full contents of a `.cue` file, already decoded as text (cue sheets
     *             are usually plain ASCII/UTF-8/Latin-1; this function does no encoding
     *             detection of its own — decode with whatever charset detection your
     *             pipeline already applies to other text-based library files, or default
     *             to UTF-8, before calling this).
     * @return A [CueSheet], or `null` if [text] contains no `FILE` line (i.e. it isn't a
     *         cue sheet describing a single-file rip at all) or no `TRACK` blocks.
     */
    fun parse(text: String): CueSheet? {
        var fileName: String? = null
        var albumTitle: String? = null
        var albumPerformer: String? = null

        val tracks = mutableListOf<CueTrack>()

        var currentTrackNumber: Int? = null
        var currentTrackTitle: String? = null
        var currentTrackPerformer: String? = null
        var currentTrackStartMs: Long? = null
        var sawAnyFileLine = false

        fun flushCurrentTrack() {
            val number = currentTrackNumber ?: return
            val startMs = currentTrackStartMs ?: return // A TRACK with no INDEX 01 is malformed — skip it.
            tracks.add(
                    CueTrack(
                            number = number,
                            title = currentTrackTitle,
                            performer = currentTrackPerformer ?: albumPerformer,
                            startMs = startMs,
                            endMs = null // filled in below, once every track's start time is known
                    )
            )
            currentTrackNumber = null
            currentTrackTitle = null
            currentTrackPerformer = null
            currentTrackStartMs = null
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val spaceIndex = line.indexOf(' ')
            val command = if (spaceIndex == -1) line else line.substring(0, spaceIndex)
            val rest = if (spaceIndex == -1) "" else line.substring(spaceIndex + 1).trim()

            when (command.uppercase()) {
                "FILE" -> {
                    // Only the first FILE line is honored — see the multi-FILE caveat above.
                    if (!sawAnyFileLine) {
                        fileName = parseQuoted(rest)
                        sawAnyFileLine = true
                    }
                }

                "TRACK" -> {
                    // A new TRACK block starts — commit whatever track we were building.
                    flushCurrentTrack()
                    currentTrackNumber = rest.substringBefore(' ').toIntOrNull()
                }

                "TITLE" -> {
                    val title = parseQuoted(rest)
                    if (currentTrackNumber != null) {
                        currentTrackTitle = title
                    } else {
                        albumTitle = title
                    }
                }

                "PERFORMER" -> {
                    val performer = parseQuoted(rest)
                    if (currentTrackNumber != null) {
                        currentTrackPerformer = performer
                    } else {
                        albumPerformer = performer
                    }
                }

                "INDEX" -> {
                    // "INDEX 01 04:32:08" — the number before the timestamp is the index type.
                    // 01 is the real start of the track; 00 (a pre-gap marker) is intentionally
                    // ignored, matching how every mainstream player treats cue sheets.
                    val parts = rest.split(' ', limit = 2)
                    if (parts.size == 2 && parts[0] == "01") {
                        currentTrackStartMs = parseCueTimestampToMs(parts[1])
                    }
                }

                // CATALOG, FLAGS, PREGAP, POSTGAP, ISRC, REM, and anything else are intentionally
                // ignored — none of them affect where a track starts or ends.
            }
        }

        flushCurrentTrack()

        if (fileName == null || tracks.isEmpty()) {
            return null
        }

        // Every track's end is simply the next track's start; the final track has no end
        // (it plays to the physical end of the file) — see CueTrack's kdoc.
        val sorted = tracks.sortedBy { it.number }
        val withEnds = sorted.mapIndexed { index, track ->
            val nextStart = sorted.getOrNull(index + 1)?.startMs
            track.copy(endMs = nextStart)
        }

        return CueSheet(
                fileName = fileName,
                albumTitle = albumTitle,
                albumPerformer = albumPerformer,
                tracks = withEnds
        )
    }

    /** Strips a leading/trailing pair of double quotes, if present — most cue fields are quoted, but not all rippers bother. */
    private fun parseQuoted(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    /**
     * Converts a cue sheet timestamp, always `MM:SS:FF` (minutes:seconds:frames, 75 frames
     * per second — the CD-audio frame rate, unrelated to video framerates) into milliseconds.
     */
    private fun parseCueTimestampToMs(timestamp: String): Long? {
        val parts = timestamp.split(':')
        if (parts.size != 3) return null

        val minutes = parts[0].toLongOrNull() ?: return null
        val seconds = parts[1].toLongOrNull() ?: return null
        val frames = parts[2].toLongOrNull() ?: return null

        val totalSeconds = minutes * 60 + seconds
        val frameMs = (frames * 1000L) / 75L
        return totalSeconds * 1000L + frameMs
    }
}
