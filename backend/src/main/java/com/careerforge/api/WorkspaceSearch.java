package com.careerforge.api;

import com.careerforge.core.ApiError;

final class WorkspaceSearch {
    private WorkspaceSearch() {}

    static String term(String query) {
        String term = query.strip();
        if (term.length() > 120) throw ApiError.invalid("Search is too long");
        return term;
    }

    static String contains(String term) {
        if (term.isEmpty()) return "";
        return "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
