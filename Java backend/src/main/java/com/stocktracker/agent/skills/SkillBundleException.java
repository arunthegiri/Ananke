package com.stocktracker.agent.skills;

import java.util.List;

/** The skill bundle failed verification. Thrown at startup so the backend refuses to boot (§9.4). */
public class SkillBundleException extends RuntimeException {

    private final List<String> problems;

    public SkillBundleException(List<String> problems) {
        super("Agent skill bundle failed verification — refusing to start:\n  - "
              + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
