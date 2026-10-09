package com.ekina.bootstrap.controller;

import com.ekina.application.agent.LocalFileIndexService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hot-editable allowed folders for the PC-assistant (代理人).
 */
@RestController
@RequestMapping("/api/agent/roots")
public class AgentRootsController {

    @Autowired
    private LocalFileIndexService localFileIndexService;

    @GetMapping
    public Map<String, Object> list() {
        return localFileIndexService.listRoots();
    }

    @PutMapping
    public Map<String, Object> put(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) body = Map.of();
        Object text = body.get("text");
        if (text != null && !String.valueOf(text).isBlank()) {
            return localFileIndexService.setCustomRootsFromText(String.valueOf(text));
        }
        Object roots = body.get("roots");
        List<String> list = new ArrayList<>();
        if (roots instanceof List<?> arr) {
            for (Object o : arr) {
                if (o != null) list.add(String.valueOf(o));
            }
        }
        return localFileIndexService.setCustomRoots(list);
    }
}
