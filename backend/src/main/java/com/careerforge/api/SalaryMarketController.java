package com.careerforge.api;

import com.careerforge.career.SalaryMarketResearch;
import com.careerforge.core.Db;
import com.careerforge.security.AuthRateLimit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/salary-research")
public class SalaryMarketController {
    private final Db db;
    private final AuthRateLimit limits;
    private final SalaryMarketResearch research;

    public SalaryMarketController(Db db, AuthRateLimit limits, SalaryMarketResearch research) {
        this.db = db;
        this.limits = limits;
        this.research = research;
    }

    @GetMapping("/capabilities")
    public Map<String, Boolean> capabilities() {
        return Map.of("onlineSearch", research.configured());
    }

    @PostMapping
    public SalaryMarketResearch.Result estimate(@Valid @RequestBody SalaryMarketResearch.Criteria criteria, HttpServletRequest request) {
        if (research.configured()) limits.check("salary-research", db.uid().toString(), 6, 300, request);
        return research.estimate(criteria);
    }
}
