package com.stocktracker.packs.cloud.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in live check (AWS_MCP_LIVE=1): the real AWS MCP Server, UNSIGNED — documentation
 * search needs no AWS account or credentials and is free. Never runs in the normal suite.
 */
@EnabledIfEnvironmentVariable(named = "AWS_MCP_LIVE", matches = "1")
class AwsMcpLiveCheck {

    @Test
    void realServer_listsKnowledgeTools_andSearchesDocs() throws Exception {
        ObjectMapper om = new ObjectMapper();
        AwsMcpKnowledge mcp = new AwsMcpKnowledge(URI.create("https://aws-mcp.us-east-1.api.aws/mcp"), "us-east-1",
                HttpClient.newHttpClient(), om, Clock.systemUTC());
        List<AwsMcpKnowledge.McpTool> tools = mcp.tools(() -> null);
        System.out.println("LIVE tools: " + tools.stream().map(AwsMcpKnowledge.McpTool::name).toList());
        tools.forEach(t -> System.out.println("LIVE schema " + t.name() + ": " + t.inputSchema()));
        assertThat(tools).isNotEmpty();
        assertThat(tools).extracting(AwsMcpKnowledge.McpTool::name).doesNotContain("aws___run_script", "aws___get_presigned_url");
        var search = tools.stream().filter(t -> t.name().equals("aws___search_documentation")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) search.inputSchema().get("properties");
        String field = props.keySet().stream().filter(k -> k.contains("search") || k.contains("query")).findFirst().orElse("search_phrase");
        Map<String, Object> out = mcp.call(() -> null, "aws___search_documentation",
                om.readTree("{\"" + field + "\":\"EC2 g6 instance NVIDIA L4\"}"));
        System.out.println("LIVE result (first 600): " + String.valueOf(out.get("content")).substring(0, Math.min(600, String.valueOf(out.get("content")).length())));
        assertThat(out.get("isError")).isEqualTo(false);
        assertThat(String.valueOf(out.get("content"))).isNotBlank();
    }
}
