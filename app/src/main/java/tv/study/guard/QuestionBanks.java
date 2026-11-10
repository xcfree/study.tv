package tv.study.guard;

import android.content.Context;
import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import org.json.JSONException;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PushbackReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

final class QuestionBanks {
    static final int MAX_SUBJECTS = 9;
    static final int MAX_QUESTIONS = 999;
    static final int UPLOAD_LIMIT_MB = 8;
    static final String DEFAULT_SUBJECT = "默认学科";
    static final String BUILTIN_SUBJECT = "数学";
    static final String MIXED = "";
    static final String BUILTIN = "@builtin";

    private static final String SUBJECT_PREFIX = "subject:";
    private static final String FILE_PREFIX = "questions-";
    private static final String FILE_SUFFIX = ".bank";
    private static final String DATA_SUFFIX = ".data";
    private static final int MAGIC = 0x53545131; // STQ1
    private static final int OFFSET_BYTES = 4;
    private static final int INDEX_START = 8;
    private static final int DATA_START = INDEX_START + MAX_QUESTIONS * OFFSET_BYTES;
    private static final int BASE64_FLAGS = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;
    private static final Pattern SUBJECT_NAME = Pattern.compile("[^\\p{Cc}\\p{Cf}\\p{Cs}]{1,20}");

    private final File directory;
    private final File legacyFile;
    private final File indexFile;
    private final Map<String, Bank> subjects = new TreeMap<>();
    final Bank builtin;

    QuestionBanks(Context context) {
        directory = context.getFilesDir();
        legacyFile = LocalContent.legacyQuestionsFile(context);
        indexFile = new File(directory, "question-banks.json");
        builtin = new Bank("内置题库", LocalContent.loadBundledQuestions(context));
        if (indexFile.isFile()) {
            if (loadIndex()) discardUnreferencedData();
            return;
        }

        File[] files = directory.listFiles((dir, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX));
        if (files != null) {
            for (File file : files) {
                try {
                    String encoded = file.getName().substring(FILE_PREFIX.length(),
                            file.getName().length() - FILE_SUFFIX.length());
                    String name = new String(Base64.decode(encoded, BASE64_FLAGS), StandardCharsets.UTF_8);
                    if (file.equals(subjectFile(name))) loadBank(name, file);
                } catch (IllegalArgumentException ignored) {
                    // Keep the other subjects usable if a file is damaged.
                }
            }
        }
        if (!subjects.containsKey(DEFAULT_SUBJECT)) loadBank(DEFAULT_SUBJECT, legacyFile);
        discardUnreferencedData();
    }

    private boolean loadIndex() {
        try (JsonReader reader = new JsonReader(new InputStreamReader(new FileInputStream(indexFile),
                StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                String filename = reader.nextString();
                File file = new File(directory, filename);
                if (file.equals(subjectFile(name)) || (DEFAULT_SUBJECT.equals(name) && file.equals(legacyFile))
                        || (filename.startsWith(FILE_PREFIX) && filename.endsWith(DATA_SUFFIX)
                        && file.getName().equals(filename))) {
                    loadBank(name, file);
                }
            }
            reader.endObject();
            return reader.peek() == JsonToken.END_DOCUMENT;
        } catch (IOException | IllegalStateException ignored) {
            // Keep the readable entries if the index was damaged.
            return false;
        }
    }

    private void discardUnreferencedData() {
        Set<File> referenced = new HashSet<>();
        for (Bank bank : subjects.values()) referenced.add(bank.file);
        File[] files = directory.listFiles((dir, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(DATA_SUFFIX));
        if (files != null) {
            for (File file : files) {
                if (!referenced.contains(file)) file.delete();
            }
        }
    }

    private void loadBank(String name, File file) {
        if (file.equals(legacyFile)) {
            List<LocalContent.Question> legacy = LocalContent.loadLegacyQuestions(legacyFile);
            if (!legacy.isEmpty()) subjects.put(DEFAULT_SUBJECT, new Bank(DEFAULT_SUBJECT, legacy));
            return;
        }
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.readInt() != MAGIC) return;
            int size = input.readInt();
            if (size >= 1 && size <= MAX_QUESTIONS && input.length() > DATA_START) {
                subjects.put(name, new Bank(name, size, file));
            }
        } catch (IOException ignored) {
            // Keep the other subjects usable if a file is damaged.
        }
    }

    static String subjectSource(String name) {
        return SUBJECT_PREFIX + name;
    }

    boolean hasSource(String source) {
        return MIXED.equals(source) || BUILTIN.equals(source) || subject(source) != null;
    }

    private Bank subject(String source) {
        return source.startsWith(SUBJECT_PREFIX) ? subjects.get(source.substring(SUBJECT_PREFIX.length())) : null;
    }

    List<Bank> subjects() {
        return new ArrayList<>(subjects.values());
    }

    List<Bank> practiceBanks() {
        List<Bank> available = subjects();
        boolean hasImportedMath = subjects.containsKey(BUILTIN_SUBJECT);
        available.add(builtin);
        Collections.sort(available, (first, second) -> Integer.compare(
                practicePriority(first, hasImportedMath), practicePriority(second, hasImportedMath)));
        return available;
    }

    private int practicePriority(Bank bank, boolean hasImportedMath) {
        return subjectPriority(bank == builtin && !hasImportedMath ? BUILTIN_SUBJECT : bank.name);
    }

    static int subjectPriority(String name) {
        switch (name) {
            case "语文": return 0;
            case BUILTIN_SUBJECT: return 1;
            case "英语": return 2;
            default: return 3;
        }
    }

    boolean contains(Bank bank) {
        return bank == builtin || subjects.get(bank.name) == bank;
    }

    List<Bank> activeBanks(String source) {
        Bank bank = subject(source);
        if (bank != null) return Collections.singletonList(bank);
        if (BUILTIN.equals(source) || subjects.isEmpty()) return Collections.singletonList(builtin);
        return subjects();
    }

    int count(String source) {
        Bank bank = subject(source);
        if (bank != null) return bank.size;
        return BUILTIN.equals(source) || subjects.isEmpty() ? builtin.size : importedCount();
    }

    int capacity(String source) {
        int count = count(source);
        return MIXED.equals(source) ? Math.max(count, builtin.size) : count;
    }

    private int importedCount() {
        int total = 0;
        for (Bank bank : subjects.values()) total += bank.size;
        return total;
    }

    private File subjectFile(String name) {
        String encoded = Base64.encodeToString(name.getBytes(StandardCharsets.UTF_8), BASE64_FLAGS);
        return new File(directory, FILE_PREFIX + encoded + FILE_SUFFIX);
    }

    void install(PreparedImport prepared, String source, int required) throws IOException {
        Map<String, Bank> next = new TreeMap<>(subjects);
        for (Map.Entry<String, BankWriter> entry : prepared.banks.entrySet()) {
            String name = entry.getKey();
            BankWriter writer = entry.getValue();
            next.put(name, new Bank(name, writer.size, writer.temp));
        }
        if (next.size() > MAX_SUBJECTS) {
            throw new IOException("最多导入 " + MAX_SUBJECTS + " 个学科");
        }
        if (MIXED.equals(source)) {
            int total = 0;
            for (Bank bank : next.values()) total += bank.size;
            requireCapacity(Math.max(total, builtin.size), required);
        } else if (source.startsWith(SUBJECT_PREFIX)) {
            BankWriter selected = prepared.banks.get(source.substring(SUBJECT_PREFIX.length()));
            if (selected != null) requireCapacity(selected.size, required);
        }

        List<File> staged = new ArrayList<>();
        boolean committed = false;
        try {
            for (String name : prepared.banks.keySet()) {
                Bank bank = next.get(name);
                File file = File.createTempFile(FILE_PREFIX, DATA_SUFFIX, directory);
                staged.add(file);
                if (!bank.file.renameTo(file)) throw new IOException("无法保存题库");
                next.put(name, new Bank(name, bank.size, file));
            }
            commit(next);
            committed = true;
        } finally {
            if (!committed) for (File file : staged) file.delete();
        }
    }

    String delete(String name, String source, int required) throws IOException {
        Bank bank = subjects.get(name);
        if (bank == null) throw new IOException("该学科已删除，请刷新页面");
        String nextSource = subjectSource(name).equals(source) ? MIXED : source;
        if (MIXED.equals(nextSource)) {
            requireCapacity(Math.max(importedCount() - bank.size, builtin.size), required);
        }
        Map<String, Bank> next = new TreeMap<>(subjects);
        next.remove(name);
        commit(next);
        return nextSource;
    }

    private void commit(Map<String, Bank> next) throws IOException {
        // Publish all changes with one rename. The previous banks stay readable until then.
        File temp = File.createTempFile("import-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temp);
                    JsonWriter writer = new JsonWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                writer.beginObject();
                for (Bank bank : next.values()) {
                    writer.name(bank.name).value(bank.file == null ? legacyFile.getName() : bank.file.getName());
                }
                writer.endObject();
                writer.flush();
                output.getFD().sync();
            }
            if (!temp.renameTo(indexFile)) throw new IOException("无法保存题库目录");
        } finally {
            temp.delete();
        }
        for (Bank bank : subjects.values()) {
            if (bank.file != null && next.get(bank.name) != bank) bank.file.delete();
        }
        Bank defaultBank = next.get(DEFAULT_SUBJECT);
        if (defaultBank == null || defaultBank.file != null) legacyFile.delete();
        subjects.clear();
        subjects.putAll(next);
    }

    static void requireCapacity(int size, int required) throws IOException {
        if (size < required) throw new IOException("出题范围至少需要 " + required + " 道题，请先调低每轮题数");
    }

    static final class Bank {
        final String name;
        final int size;
        private final File file;
        private final List<LocalContent.Question> questions;

        Bank(String name, List<LocalContent.Question> questions) {
            this.name = name;
            this.size = questions.size();
            this.questions = questions;
            this.file = null;
        }

        Bank(String name, int size, File file) {
            this.name = name;
            this.size = size;
            this.questions = null;
            this.file = file;
        }

        LocalContent.Question questionAt(int index) throws IOException {
            if (file == null) return questions.get(index);
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                input.seek(INDEX_START + index * OFFSET_BYTES);
                int offset = input.readInt();
                long length = input.length();
                long end = index + 1 < size ? input.readInt() : length;
                if (offset < INDEX_START + size * OFFSET_BYTES || offset >= end || end > length) {
                    throw new IOException("题库索引已损坏");
                }
                input.seek(offset);
                String prompt = input.readUTF();
                int optionCount = input.readUnsignedByte();
                int answer = input.readUnsignedByte();
                if (optionCount < 2 || optionCount > 6 || answer >= optionCount) throw new IOException("题目已损坏");
                List<String> options = new ArrayList<>(optionCount);
                for (int i = 0; i < optionCount; i++) options.add(input.readUTF());
                // Older records end after the options. An explanation extends just this record.
                String explanation = input.getFilePointer() < end ? input.readUTF() : "";
                if (input.getFilePointer() != end) throw new IOException("题目已损坏");
                return new LocalContent.Question(prompt, options, answer, explanation);
            }
        }
    }

    static final class PreparedImport implements AutoCloseable {
        private final Map<String, BankWriter> banks = new LinkedHashMap<>();

        Set<String> names() {
            return banks.keySet();
        }

        @Override public void close() {
            for (BankWriter bank : banks.values()) bank.close();
        }
    }

    static String validateName(String input) throws JSONException {
        String name = input.trim();
        if (!SUBJECT_NAME.matcher(name).matches()) {
            throw new JSONException("学科名称须为 1–20 字，不能包含控制字符");
        }
        return name;
    }

    static PreparedImport prepareImport(Context context, String fallbackName, InputStream input)
            throws IOException, JSONException {
        PreparedImport prepared = new PreparedImport();
        boolean ready = false;
        try {
            try (PushbackReader text = new PushbackReader(new InputStreamReader(input,
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)), 1);
                    JsonReader reader = new JsonReader(text)) {
                int first = text.read();
                if (first != -1 && first != '\uFEFF') text.unread(first);
                reader.beginArray();
                int count = 0;
                while (reader.hasNext()) {
                    ImportQuestion record;
                    try {
                        record = readQuestion(reader, fallbackName);
                    } catch (JSONException | IOException | IllegalStateException e) {
                        throw new JSONException("第 " + (count + 1) + " 题格式错误：" + e.getMessage());
                    }
                    BankWriter bank = prepared.banks.get(record.subject);
                    if (bank == null) {
                        if (prepared.banks.size() == MAX_SUBJECTS) {
                            throw new JSONException("最多导入 " + MAX_SUBJECTS + " 个学科");
                        }
                        bank = new BankWriter(context.getFilesDir());
                        prepared.banks.put(record.subject, bank);
                    }
                    bank.append(record.question);
                    count++;
                }
                reader.endArray();
                if (count == 0) throw new JSONException("题库至少须包含 1 道题");
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new JSONException("题库 JSON 含多余内容");
            } catch (IllegalStateException e) {
                throw new JSONException("题库须为 JSON 数组");
            }
            for (BankWriter bank : prepared.banks.values()) bank.finish();
            ready = true;
            return prepared;
        } finally {
            if (!ready) prepared.close();
        }
    }

    private static final class BankWriter {
        final File temp;
        private final DataOutputStream output;
        private final int[] offsets = new int[MAX_QUESTIONS];
        int size;

        BankWriter(File directory) throws IOException {
            temp = File.createTempFile("import-", ".tmp", directory);
            try {
                output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temp)));
            } catch (IOException e) {
                temp.delete();
                throw e;
            }
        }

        void append(LocalContent.Question question) throws IOException, JSONException {
            if (size == MAX_QUESTIONS) throw new JSONException("每个学科最多 " + MAX_QUESTIONS + " 道题");
            if (size == 0) output.write(new byte[DATA_START]);
            offsets[size++] = output.size();
            output.writeUTF(question.prompt);
            output.writeByte(question.options.size());
            output.writeByte(question.answer);
            for (String option : question.options) output.writeUTF(option);
            if (!question.explanation.isEmpty()) output.writeUTF(question.explanation);
        }

        void finish() throws IOException {
            output.close();
            ByteBuffer header = ByteBuffer.allocate(INDEX_START + size * OFFSET_BYTES);
            header.putInt(MAGIC).putInt(size);
            for (int i = 0; i < size; i++) header.putInt(offsets[i]);
            try (RandomAccessFile file = new RandomAccessFile(temp, "rw")) {
                file.write(header.array());
                file.getFD().sync();
            }
        }

        void close() {
            try { output.close(); } catch (IOException ignored) {}
            temp.delete();
        }
    }

    private static final class ImportQuestion {
        final String subject;
        final LocalContent.Question question;

        ImportQuestion(String subject, LocalContent.Question question) {
            this.subject = subject;
            this.question = question;
        }
    }

    private static ImportQuestion readQuestion(JsonReader reader, String fallbackName)
            throws IOException, JSONException {
        String subject = null;
        String prompt = null;
        String explanation = "";
        List<String> options = null;
        int answer = -1;
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "subject":
                    if (subject != null) throw new JSONException("subject 字段重复");
                    if (reader.peek() != JsonToken.STRING) throw new JSONException("subject 必须是字符串");
                    subject = validateName(reader.nextString());
                    break;
                case "question":
                    prompt = readText(reader, 180);
                    break;
                case "options":
                    options = new ArrayList<>();
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (options.size() == 6) throw new JSONException("每题须有 2–6 个选项");
                        options.add(readText(reader, 65));
                    }
                    reader.endArray();
                    if (options.size() < 2) throw new JSONException("每题须有 2–6 个选项");
                    if (new HashSet<>(options).size() != options.size()) throw new JSONException("选项不能重复");
                    break;
                case "answer":
                    if (reader.peek() != JsonToken.NUMBER) throw invalidAnswer();
                    try {
                        answer = Integer.parseInt(reader.nextString());
                    } catch (NumberFormatException e) {
                        throw invalidAnswer();
                    }
                    break;
                case "explanation":
                    explanation = readText(reader, LocalContent.MAX_EXPLANATION_LENGTH);
                    break;
                default:
                    reader.skipValue();
            }
        }
        reader.endObject();
        if (prompt == null || options == null) throw new JSONException("每题须包含 question 和 options");
        if (answer < 0 || answer >= options.size()) throw invalidAnswer();
        return new ImportQuestion(subject == null ? fallbackName : subject,
                new LocalContent.Question(prompt, options, answer, explanation));
    }

    private static String readText(JsonReader reader, int max) throws IOException, JSONException {
        if (reader.peek() != JsonToken.STRING) throw new JSONException("文字字段必须是字符串");
        return LocalContent.readText(reader.nextString(), max);
    }

    private static JSONException invalidAnswer() {
        return new JSONException("answer 必须是从 0 起算的选项编号");
    }
}
