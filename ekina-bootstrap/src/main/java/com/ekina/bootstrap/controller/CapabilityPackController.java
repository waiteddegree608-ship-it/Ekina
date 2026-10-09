package com.ekina.bootstrap.controller;

import com.ekina.application.agentreg.CapabilityPackCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/capability-packs")
public class CapabilityPackController {

    @GetMapping
    public List<Map<String, Object>> list() {
        return CapabilityPackCatalog.listPacks();
    }
}
