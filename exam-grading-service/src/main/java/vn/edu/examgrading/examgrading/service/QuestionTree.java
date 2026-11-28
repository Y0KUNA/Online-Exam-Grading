package vn.edu.examgrading.examgrading.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Converts the flat relational question list into the recursive rubric contract. */
final class QuestionTree {
    private QuestionTree() { }

    static List<Map<String, Object>> fromRows(List<Map<String, Object>> rows) {
        Map<UUID, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> node = new LinkedHashMap<>(row);
            node.put("children", new ArrayList<Map<String, Object>>());
            byId.put((UUID) node.get("questionId"), node);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> node : byId.values()) {
            UUID parentId = (UUID) node.get("parentQuestionId");
            Map<String, Object> parent = parentId == null ? null : byId.get(parentId);
            if (parent == null) roots.add(node);
            else {
                @SuppressWarnings("unchecked") List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
                children.add(node);
            }
        }
        return roots;
    }
}
