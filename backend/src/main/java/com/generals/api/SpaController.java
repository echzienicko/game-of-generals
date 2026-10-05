package com.generals.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards single-page application routes to index.html so React Router handles them.
 */
@Controller
public class SpaController {

    @GetMapping(value = {"/game/{id}", "/lobby", "/vs-bot", "/leaderboard"})
    public String forward() {
        return "forward:/index.html";
    }
}