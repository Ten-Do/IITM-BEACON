package com.iitm.beacon.analytics;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The homepage dashboard page at {@code /} (UC-VIEW-DASHBOARD, decision
 * 30): the same figures {@link AnalyticsController} answers as JSON, from
 * the same {@link AnalyticsService}, rendered with {@link DashboardView}'s
 * presentation on top. Public and read-only; HEAD comes with GET.
 */
@Controller
public class AnalyticsViewController {

    private static final String DASHBOARD_VIEW = "analytics/dashboard";

    private final AnalyticsService analyticsService;
    private final WorldMap worldMap;

    AnalyticsViewController(AnalyticsService analyticsService, WorldMap worldMap) {
        this.analyticsService = analyticsService;
        this.worldMap = worldMap;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("dashboard", DashboardView.of(analyticsService.summary(), worldMap));
        return DASHBOARD_VIEW;
    }
}
