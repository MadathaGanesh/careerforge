package com.careerforge.api;

import com.careerforge.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final Db db;

    public SearchController(Db db) {
        this.db = db;
    }

    /**
     * Search the authenticated user's SAVED jobs and applications,
     * not external job boards.
     */
    @GetMapping
    public Map<String, Object> search(@RequestParam(defaultValue = "") String q) {

        log.debug("Search request received: queryLength={}",
                q.length());

        String term = WorkspaceSearch.term(q);

        log.debug("Search query normalized: termLength={}",
                term.length());

        if (term.length() < 2) {
            log.debug("Search skipped: query is shorter than minimum required length, termLength={}",
                    term.length());

            return Map.of(
                    "jobs", List.of(),
                    "applications", List.of(),
                    "scope", "SAVED_CAREERFORGE_DATA"
            );
        }

        String like = WorkspaceSearch.contains(term);

        log.debug("Search pattern prepared successfully: termLength={}",
                term.length());

        log.debug("Searching saved jobs");

        var jobs = db.list("""
                SELECT id,company_name,title,location,created_at
                FROM jobs
                WHERE user_id=? AND deleted_at IS NULL
                  AND (company_name ILIKE ? ESCAPE '\\' OR title ILIKE ? ESCAPE '\\')
                ORDER BY created_at DESC LIMIT 15
                """, db.uid(), like, like);

        log.debug("Saved jobs search completed: resultsCount={}",
                jobs.size());

        log.debug("Searching saved applications");

        var applications = db.list("""
                SELECT a.id,a.status,j.company_name,j.title,j.location,a.updated_at
                FROM applications a JOIN jobs j ON j.id=a.job_id
                WHERE a.user_id=? AND j.deleted_at IS NULL
                  AND (j.company_name ILIKE ? ESCAPE '\\' OR j.title ILIKE ? ESCAPE '\\')
                ORDER BY a.updated_at DESC LIMIT 15
                """, db.uid(), like, like);

        log.debug("Saved applications search completed: resultsCount={}",
                applications.size());

        log.info("Search completed successfully: jobsCount={}, applicationsCount={}, scope={}",
                jobs.size(), applications.size(), "SAVED_CAREERFORGE_DATA");

        return Map.of(
                "jobs", jobs,
                "applications", applications,
                "scope", "SAVED_CAREERFORGE_DATA"
        );
    }
}

