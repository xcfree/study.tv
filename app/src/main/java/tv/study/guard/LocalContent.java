package tv.study.guard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

final class LocalContent {
    enum Kind {
        IMAGE("wallpaper.image", 24 * 1024 * 1024L),
        VIDEO("wallpaper.video", 200 * 1024 * 1024L);

        final String fileName;
        final long limit;

        Kind(String fileName, long limit) {
            this.fileName = fileName;
            this.limit = limit;
        }
    }

    private static final String LEGACY_QUESTIONS = "questions.json";
    private static final int LEGACY_QUESTIONS_LIMIT = 1024 * 1024;
    static final int MAX_EXPLANATION_LENGTH = 260;

    static final class Question {
        final String prompt;
        final List<String> options;
        final int answer;
        final String explanation;

        Question(String prompt, List<String> options, int answer, String explanation) {
            this.prompt = prompt;
            this.options = options;
            this.answer = answer;
            this.explanation = explanation;
        }

        Question shuffle() {
            List<String> shuffled = new ArrayList<>(options);
            Collections.shuffle(shuffled);
            return new Question(prompt, shuffled, shuffled.indexOf(options.get(answer)), explanation);
        }
    }

    private static List<Question> parse(InputStream input) throws IOException, JSONException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int size;
        while ((size = input.read(buffer)) != -1) {
            if (bytes.size() + size > LEGACY_QUESTIONS_LIMIT) throw new IOException("题库文件过大");
            bytes.write(buffer, 0, size);
        }
        String json = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        if (json.startsWith("\uFEFF")) json = json.substring(1);
        JSONArray list = new JSONArray(json);
        if (list.length() < 1 || list.length() > 500) throw new JSONException("题库须包含 1–500 道题");
        List<Question> result = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            try {
                JSONObject item = list.getJSONObject(i);
                String prompt = readText(item.get("question"), 180);
                JSONArray choices = item.getJSONArray("options");
                if (choices.length() < 2 || choices.length() > 6) throw new JSONException("每题须有 2–6 个选项");
                List<String> options = new ArrayList<>();
                for (int j = 0; j < choices.length(); j++) options.add(readText(choices.get(j), 65));
                if (new HashSet<>(options).size() != options.size()) throw new JSONException("选项不能重复");
                Object answer = item.get("answer");
                if (!(answer instanceof Integer) || (int) answer < 0 || (int) answer >= options.size()) {
                    throw new JSONException("answer 必须是从 0 起算的选项编号");
                }
                String explanation = item.has("explanation")
                        ? readText(item.get("explanation"), MAX_EXPLANATION_LENGTH) : "";
                result.add(new Question(prompt, options, (int) answer, explanation));
            } catch (JSONException e) {
                throw new JSONException("第 " + (i + 1) + " 题格式错误：" + e.getMessage());
            }
        }
        return result;
    }

    static String readText(Object value, int max) throws JSONException {
        if (!(value instanceof String)) throw new JSONException("文字字段必须是字符串");
        String result = ((String) value).trim();
        if (result.isEmpty() || result.length() > max) throw new JSONException("文字字段须为 1–" + max + " 字");
        return result;
    }

    static File legacyQuestionsFile(Context context) {
        return new File(context.getFilesDir(), LEGACY_QUESTIONS);
    }

    static List<Question> loadLegacyQuestions(File file) {
        try (InputStream input = new FileInputStream(file)) {
            return parse(input);
        } catch (IOException | JSONException ignored) {
            return Collections.emptyList();
        }
    }

    static List<Question> loadBundledQuestions(Context context) {
        try (InputStream input = context.getAssets().open(LEGACY_QUESTIONS)) {
            return parse(input);
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("Bundled questions are invalid", e);
        }
    }

    static File file(Context context, Kind kind) {
        return new File(context.getFilesDir(), kind.fileName);
    }

    static void discardInterruptedImports(Context context) {
        File[] files = context.getFilesDir().listFiles((directory, name) ->
                name.startsWith("import-") && name.endsWith(".tmp"));
        if (files == null) return;
        for (File file : files) file.delete();
    }

    static final class PreparedImport implements AutoCloseable {
        final Kind kind;
        private final File temp;
        PreparedImport(Kind kind, File temp) {
            this.kind = kind;
            this.temp = temp;
        }

        void install(Context context) throws IOException {
            if (!temp.renameTo(file(context, kind))) throw new IOException("无法保存文件");
        }

        @Override public void close() {
            temp.delete();
        }
    }

    static PreparedImport prepareImport(Context context, Kind kind, InputStream input)
            throws IOException, JSONException {
        if (input == null) throw new IOException("无法读取文件");
        File temp = null;
        boolean ready = false;
        try {
            try (InputStream source = input) {
                temp = File.createTempFile("import-", ".tmp", context.getFilesDir());
                try (FileOutputStream output = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[32768];
                    long total = 0;
                    int count;
                    while ((count = source.read(buffer)) != -1) {
                        total += count;
                        if (total > kind.limit) {
                            throw new IOException("文件超过 " + kind.limit / (1024 * 1024) + " MB");
                        }
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
            }
            switch (kind) {
                case IMAGE:
                    Bitmap bitmap = readImage(temp, 256, 144);
                    if (bitmap == null) throw new IOException("电视无法读取这张图片");
                    bitmap.recycle();
                    break;
                case VIDEO:
                    validateVideo(temp);
                    break;
            }
            ready = true;
            return new PreparedImport(kind, temp);
        } finally {
            if (!ready && temp != null) temp.delete();
        }
    }

    static Bitmap readImage(File file, int width, int height) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), opts);
        if (opts.outWidth < 1 || opts.outHeight < 1) return null;
        int sample = 1;
        while (opts.outWidth / sample > width * 2 || opts.outHeight / sample > height * 2) sample *= 2;
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getPath(), opts);
    }

    private static void validateVideo(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getPath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String type = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (type != null && type.startsWith("video/")) return;
            }
            throw new IOException("文件没有可识别的视频轨道");
        } catch (RuntimeException e) {
            throw new IOException("电视无法读取这个视频", e);
        } finally {
            extractor.release();
        }
    }
}
