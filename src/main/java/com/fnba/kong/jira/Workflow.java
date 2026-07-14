package com.fnba.kong.jira;

import java.util.List;

/**
 * A Jira workflow definition, reduced to what Kong needs to draw it: the ordered
 * status list and the transitions between them. Built by
 * {@link JiraClient#workflow(String)} from the workflow bulk-read API.
 */
public record Workflow(String id, String name, List<Status> statuses, List<Transition> transitions) {

    /**
     * A workflow status.
     * @param ref      Jira's stable status reference (used as a node id)
     * @param name     display name
     * @param category category key: {@code new} | {@code indeterminate} | {@code done}
     */
    public record Status(String ref, String name, String category) {}

    /**
     * A workflow transition.
     * @param name     display name (used as the edge label)
     * @param type     {@code INITIAL} | {@code GLOBAL} | {@code DIRECTED}
     * @param fromRefs source status refs (empty for INITIAL and GLOBAL)
     * @param toRef    destination status ref
     */
    public record Transition(String name, String type, List<String> fromRefs, String toRef) {}

    /**
     * Render the workflow as a <a href="https://mermaid.js.org/">Mermaid</a>
     * flowchart definition. Nodes are coloured by status category to match
     * Kong's palette; the initial transition hangs off a {@code Start} node and
     * the workflow's global transitions off a dashed {@code Any status} node.
     */
    public String toMermaid() {
        StringBuilder b = new StringBuilder("flowchart TD\n");

        for (Status s : statuses) {
            b.append("  ").append(nodeId(s.ref()))
             .append("[\"").append(esc(s.name())).append("\"]:::")
             .append(cssClass(s.category())).append('\n');
        }

        boolean hasInitial = transitions.stream().anyMatch(t -> "INITIAL".equals(t.type()));
        boolean hasGlobal  = transitions.stream().anyMatch(t -> "GLOBAL".equals(t.type()));
        if (hasInitial) b.append("  wfStart((\"Start\")):::startNode\n");
        if (hasGlobal)  b.append("  wfAny{\"Any status\"}:::anyNode\n");

        for (Transition t : transitions) {
            String label = "|\"" + esc(t.name()) + "\"| ";
            switch (t.type()) {
                case "INITIAL" -> b.append("  wfStart -->").append(label).append(nodeId(t.toRef())).append('\n');
                case "GLOBAL"  -> b.append("  wfAny -.->").append(label).append(nodeId(t.toRef())).append('\n');
                default -> {
                    for (String from : t.fromRefs()) {
                        b.append("  ").append(nodeId(from)).append(" -->").append(label)
                         .append(nodeId(t.toRef())).append('\n');
                    }
                }
            }
        }

        // Kong palette (mirrors the .status.sc-* badge colours in app.css).
        b.append("  classDef new fill:#3a3d44,stroke:#5a5d64,color:#e3e5e8;\n");
        b.append("  classDef indeterminate fill:#2a3a52,stroke:#3f5578,color:#9ec1ff;\n");
        b.append("  classDef done fill:#1f3a2a,stroke:#2f5a40,color:#8ce0a8;\n");
        b.append("  classDef startNode fill:#4f8cff,stroke:#4f8cff,color:#ffffff;\n");
        b.append("  classDef anyNode fill:#454851,stroke:#9aa0a6,color:#e3e5e8,stroke-dasharray:4 3;\n");
        return b.toString();
    }

    private static String nodeId(String ref) {
        return "s" + ref.replaceAll("[^A-Za-z0-9]", "_");
    }

    private static String cssClass(String category) {
        return switch (category) {
            case "new", "indeterminate", "done" -> category;
            default -> "new";
        };
    }

    /** Mermaid holds labels in double quotes; swap any embedded quote for a safe glyph. */
    private static String esc(String s) {
        return s == null ? "" : s.replace('"', '\'');
    }
}
