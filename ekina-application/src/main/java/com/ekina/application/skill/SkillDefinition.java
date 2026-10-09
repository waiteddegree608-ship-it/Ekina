package com.ekina.application.skill;

import java.util.ArrayList;
import java.util.List;

public class SkillDefinition {
    private String id;
    private String title;
    private String path;
    private String body;
    private List<String> tools = new ArrayList<>();
    private List<String> triggers = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public List<String> getTools() { return tools; }
    public void setTools(List<String> tools) { this.tools = tools; }
    public List<String> getTriggers() { return triggers; }
    public void setTriggers(List<String> triggers) { this.triggers = triggers; }
}
