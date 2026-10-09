package vn.edu.examgrading.common.api;

import java.util.List;

public record CursorPage<T>(List<T> items, String nextCursor) { }
