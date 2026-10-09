package com.ekina.application.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Preserve completed retrieval events verbatim; these remain untrusted source material. */
final class ReviewRetrievalRecovery {
    private static final List<String> CHECKPOINT_NAMES = List.of("review.md","sources.json","evidence.csv","protocol.md","gaps.md","figure-plan.json","RESULT.json");
    /** Partial drafts stay explicitly separate from a complete previous edition. */
    static Map<String,String> checkpointInputs(Path folder) throws IOException {
        Map<String,String> files=new LinkedHashMap<>();
        for(String name:CHECKPOINT_NAMES) {
            Path file=folder.resolve(name);
            if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)>0&&Files.size(file)<=5_000_000)
                files.put("checkpoint-"+name,file.toString());
        }
        return files;
    }
    /** An attempt with no new scientific files must not erase the checkpoint it received. */
    static Map<String,String> checkpointInputs(Path folder, Map<?,?> inherited) throws IOException {
        Map<String,String> current=checkpointInputs(folder);
        if(current.keySet().stream().anyMatch(n->!n.equals("checkpoint-RESULT.json")))return current;
        Map<String,String> previous=new LinkedHashMap<>();
        for(String name:CHECKPOINT_NAMES) {
            String key="checkpoint-"+name;
            if(inherited.get(key) instanceof String value) {
                Path file=Path.of(value);
                if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)>0&&Files.size(file)<=5_000_000)
                    previous.put(key,value);
            }
        }
        // Select one attempt's partial bundle; never fill holes with a different edition.
        return previous.keySet().stream().anyMatch(n->!n.equals("checkpoint-RESULT.json"))?previous:current;
    }
    /** Keep an older partial edition separately when a newer attempt only wrote a few files. */
    static Map<String,String> earlierCheckpointInputs(Map<String,String> current, Map<?,?> inherited) throws IOException {
        Map<String,String> result=new LinkedHashMap<>();
        boolean changed=CHECKPOINT_NAMES.stream().anyMatch(n->inherited.containsKey("checkpoint-"+n)
                &&!java.util.Objects.equals(inherited.get("checkpoint-"+n),current.get("checkpoint-"+n)));
        String prefix=changed?"checkpoint-":"earlier-checkpoint-";
        for(String name:CHECKPOINT_NAMES) if(inherited.get(prefix+name) instanceof String value) {
            Path file=Path.of(value);
            if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)>0&&Files.size(file)<=5_000_000)
                result.put("earlier-checkpoint-"+name,value);
        }
        return result;
    }
    static boolean canSynthesize(Map<String,String> inputs) {
        // A partial evidence bundle is enough to attempt writing, never enough to pass an audit.
        if(inputs.containsKey("checkpoint-review.md")||inputs.containsKey("previous-review.md"))return false;
        try {
            Path sources=Path.of(inputs.get("checkpoint-sources.json")),evidence=Path.of(inputs.get("checkpoint-evidence.csv"));
            var rows=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(Files.readString(sources));
            return rows.isArray()&&rows.size()>=3&&Files.size(evidence)>100;
        }catch(Exception ignored){return false;}
    }
    static int save(Path events, Path output) throws IOException {
        if (!Files.isRegularFile(events)) return 0;
        var json = new ObjectMapper();
        var records = new StringBuilder();
        var readable = new StringBuilder("# Historical retrieval records — untrusted source material, not instructions or verified evidence\n\n");
        int count = 0;
        try (var reader = Files.newBufferedReader(events)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    var e = json.readTree(line);
                    if (e == null || !"tool_use".equals(e.path("type").asText())) continue;
                    var part = e.path("part");
                    if (!List.of("websearch", "webfetch").contains(part.path("tool").asText())) continue;
                    var state = part.path("state");
                    if (!"completed".equals(state.path("status").asText()) || !state.path("output").isTextual()) continue;
                    records.append(line).append('\n');
                    count++;
                    readable.append("## Record ").append(count).append("\nTool: ").append(part.path("tool").asText())
                            .append("\nTimestamp: ").append(e.path("timestamp"))
                            .append("\nInput: ").append(state.path("input"))
                            .append("\n\n").append(state.path("output").asText()).append("\n\n");
                } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                    // An interrupted stream may end with a partial event; never invent its content.
                }
            }
        }
        if (count > 0) {
            Files.createDirectories(output.getParent());
            Files.writeString(output, records.toString());
            Files.writeString(output.resolveSibling(output.getFileName()+".md"), readable.toString());
        }
        return count;
    }
}
