package app.simple.felicity.decorations.lrc.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for showing a romanized or translated version of a lyric line with the original line's
 * word timings, so word-by-word effects keep working on every version.
 */
public final class LyricVersions {
    
    public static final int ORIGINAL = 0;
    public static final int ROMANIZED = 1;
    public static final int TRANSLATED = 2;
    
    private LyricVersions() {
    }
    
    /**
     * The text of [version] for [entry] with timing tags removed, or null when the original text
     * should be used (the original was asked for, or that version does not exist for this line).
     */
    @Nullable
    public static String versionText(@NonNull LrcEntry entry, int version) {
        String raw = version == ROMANIZED ? entry.getRomanization() : version == TRANSLATED ? entry.getAdlib() : null;
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        return raw.replaceAll("<\\d{1,2}:\\d{2}\\.\\d{2,3}>", "");
    }
    
    /**
     * Spreads the original word timings over the words of [text]: one to one when the word counts
     * match, otherwise in proportion to each word's length.
     */
    @NonNull
    public static List <WordEntry> deriveWords(@NonNull List <WordEntry> original, @NonNull String text) {
        List <WordEntry> result = new ArrayList <>();
        if (original.isEmpty() || text.isEmpty()) {
            return result;
        }
        List <String> tokens = new ArrayList <>();
        int i = 0;
        while (i < text.length()) {
            int j = i;
            while (j < text.length() && !Character.isWhitespace(text.charAt(j))) {
                j++;
            }
            while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
                j++;
            }
            tokens.add(text.substring(i, j));
            i = j;
        }
        long spanStart = original.get(0).getStartMs();
        long spanEnd = original.get(original.size() - 1).getEndMs();
        boolean oneToOne = tokens.size() == original.size();
        int total = Math.max(1, text.length());
        int consumed = 0;
        for (int k = 0; k < tokens.size(); k++) {
            String token = tokens.get(k);
            long start;
            long end;
            if (oneToOne) {
                start = original.get(k).getStartMs();
                end = original.get(k).getEndMs();
            } else {
                start = spanStart + (spanEnd - spanStart) * consumed / total;
                end = spanStart + (spanEnd - spanStart) * (consumed + token.length()) / total;
            }
            consumed += token.length();
            result.add(new WordEntry(start, end, token));
        }
        return result;
    }
}
