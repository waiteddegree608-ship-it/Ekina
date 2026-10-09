package com.ekina.application.paper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalized literature record. Writing may ONLY cite entries that have doi and/or arxivId.
 */
public class LitPaper {
    private String id;
    private String title;
    private String abstractText;
    private Integer year;
    private String venue;
    private String doi;
    private String arxivId;
    private String url;
    private String codeUrl;
    private String pdfUrl;
    private String tldr;
    private Integer citationCount;
    private List<String> authors = new ArrayList<>();
    private String source; // semantic_scholar | openalex | arxiv
    private String venueTier; // A / B / C (CCF-ish) or null when unclassified
    private boolean topVenue; // true when venueTier == "A" (顶会/顶刊)
    private Map<String, Object> raw = new LinkedHashMap<>();

    public boolean hasStableId() {
        return (doi != null && !doi.isBlank()) || (arxivId != null && !arxivId.isBlank());
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAbstractText() { return abstractText; }
    public void setAbstractText(String abstractText) { this.abstractText = abstractText; }
    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }
    public String getVenue() { return venue; }
    public void setVenue(String venue) { this.venue = venue; }
    public String getDoi() { return doi; }
    public void setDoi(String doi) { this.doi = doi; }
    public String getArxivId() { return arxivId; }
    public void setArxivId(String arxivId) { this.arxivId = arxivId; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getCodeUrl() { return codeUrl; }
    public void setCodeUrl(String codeUrl) { this.codeUrl = codeUrl; }
    public String getPdfUrl() { return pdfUrl; }
    public void setPdfUrl(String pdfUrl) { this.pdfUrl = pdfUrl; }
    public String getTldr() { return tldr; }
    public void setTldr(String tldr) { this.tldr = tldr; }
    public Integer getCitationCount() { return citationCount; }
    public void setCitationCount(Integer citationCount) { this.citationCount = citationCount; }
    public List<String> getAuthors() { return authors; }
    public void setAuthors(List<String> authors) {
        this.authors = authors == null ? new ArrayList<>() : authors;
    }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getVenueTier() { return venueTier; }
    public void setVenueTier(String venueTier) { this.venueTier = venueTier; }
    public boolean isTopVenue() { return topVenue; }
    public void setTopVenue(boolean topVenue) { this.topVenue = topVenue; }
    public Map<String, Object> getRaw() { return raw; }
    public void setRaw(Map<String, Object> raw) {
        this.raw = raw == null ? new LinkedHashMap<>() : raw;
    }
}
