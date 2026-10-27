package vn.edu.examgrading.identity.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** EXAM_OFFICE-only roster boundary; PII never leaves Identity for grading paths. */
@RestController
@RequestMapping("/api/v1/exams/{examId}/candidates")
@PreAuthorize("hasRole('EXAM_OFFICE')")
public class CandidateController {
    private final JdbcTemplate jdbc;

    public CandidateController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable UUID examId, @RequestParam(required = false) String query) {
        String q = query == null ? "%" : "%" + query.trim() + "%";
        return Map.of("items", jdbc.queryForList("select candidate_id as \"candidateId\",candidate_number as \"candidateNumber\",full_name as \"fullName\",class_name as \"className\" from candidate where exam_id=? and (candidate_number ilike ? or full_name ilike ?) order by candidate_number", examId, q, q));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importRoster(@PathVariable UUID examId, @RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new IllegalArgumentException("file must be a non-empty CSV file");
        }
        List<CsvRecord> records = parseCsv(new String(file.getBytes(), StandardCharsets.UTF_8));
        if (records.isEmpty()) throw new IllegalArgumentException("CSV file must include a header row");
        Map<String, Integer> columns = headerColumns(records.removeFirst().values());
        requireColumn(columns, "candidateNumber");
        requireColumn(columns, "fullName");

        int inserted = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (CsvRecord record : records) {
            if (record.values().stream().allMatch(String::isBlank)) continue;
            try {
                String number = required(value(record.values(), columns, "candidateNumber"), "candidateNumber");
                String fullName = required(value(record.values(), columns, "fullName"), "fullName");
                String className = value(record.values(), columns, "className");
                jdbc.update("insert into candidate(candidate_id,exam_id,candidate_number,full_name,class_name) values(?,?,?,?,?)", UUID.randomUUID(), examId, number, fullName, className == null || className.isBlank() ? null : className.trim());
                inserted++;
            } catch (DataIntegrityViolationException exception) {
                errors.add(error(record.line(), "DUPLICATE_CANDIDATE_NUMBER", "candidateNumber already exists in this exam"));
            } catch (Exception exception) {
                errors.add(error(record.line(), "INVALID_ROSTER_ROW", exception.getMessage() == null ? "Invalid row" : exception.getMessage()));
            }
        }
        return Map.of("inserted", inserted, "rejected", errors.size(), "errors", errors);
    }

    private static Map<String, Object> error(int row, String code, String message) {
        return Map.of("row", row, "code", code, "message", message);
    }

    private static Map<String, Integer> headerColumns(List<String> headers) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            String header = headers.get(index).replace("\uFEFF", "").trim().replaceAll("[-_\\s]", "").toLowerCase(Locale.ROOT);
            if (header.equals("candidatenumber")) columns.put("candidateNumber", index);
            if (header.equals("fullname")) columns.put("fullName", index);
            if (header.equals("classname")) columns.put("className", index);
        }
        return columns;
    }

    private static void requireColumn(Map<String, Integer> columns, String name) {
        if (!columns.containsKey(name)) throw new IllegalArgumentException("CSV header must include " + name);
    }

    private static String value(List<String> row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || index >= row.size() ? null : row.get(index);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static List<CsvRecord> parseCsv(String content) {
        List<CsvRecord> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int recordLine = 1;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < content.length() && content.charAt(index + 1) == '"') {
                    cell.append('"');
                    index++;
                } else quoted = !quoted;
            } else if (current == ',' && !quoted) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if ((current == '\n' || current == '\r') && !quoted) {
                if (current == '\r' && index + 1 < content.length() && content.charAt(index + 1) == '\n') index++;
                row.add(cell.toString());
                records.add(new CsvRecord(recordLine, List.copyOf(row)));
                row.clear();
                cell.setLength(0);
                line++;
                recordLine = line;
            } else {
                cell.append(current);
                if (current == '\n') line++;
            }
        }
        if (quoted) throw new IllegalArgumentException("CSV contains an unclosed quoted field");
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            records.add(new CsvRecord(recordLine, List.copyOf(row)));
        }
        return records;
    }

    private record CsvRecord(int line, List<String> values) { }
}
