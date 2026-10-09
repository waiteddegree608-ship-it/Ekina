package com.ekina.bootstrap.controller;

import com.ekina.application.prefs.UiPrefsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ui-prefs")
public class UiPrefsController {

    @Autowired
    private UiPrefsService uiPrefsService;

    @GetMapping
    public Map<String, Object> get() {
        return uiPrefsService.get();
    }

    @PutMapping
    public Map<String, Object> put(@RequestBody(required = false) Map<String, Object> body) {
        return uiPrefsService.put(body == null ? Map.of() : body);
    }
}
