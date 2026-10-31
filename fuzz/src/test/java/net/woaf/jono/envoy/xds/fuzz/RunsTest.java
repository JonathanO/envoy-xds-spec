package net.woaf.jono.envoy.xds.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class RunsTest {

    @Test
    void resourcesCompareAsASetOfParsedResources() {
        String one = """
            {"runs": {"sotw": {"steps": [[{"envoy": "e", "type": "CDS", "resources": [{"name": "a", "connectTimeout": "1s"}, {"name": "b"}]}]]}}}""";
        String other = """
            {"runs": {"sotw": {"steps": [[{"envoy": "e", "type": "CDS", "resources": [{"name": "b"}, {"connectTimeout": "1.000s", "name": "a"}]}]]}}}""";

        assertThat(Runs.of(JsonParser.parseString(one).getAsJsonObject())).isEqualTo(Runs.of(JsonParser.parseString(other).getAsJsonObject()));
    }

    @Test
    void differentContentDiffers() {
        String one = """
            {"runs": {"delta": {"steps": [[{"envoy": "e", "type": "CDS", "resources": [{"name": "a", "connectTimeout": "1s"}], "removed": []}]]}}}""";
        String other = """
            {"runs": {"delta": {"steps": [[{"envoy": "e", "type": "CDS", "resources": [{"name": "a", "connectTimeout": "2s"}], "removed": []}]]}}}""";

        assertThat(Runs.of(JsonParser.parseString(one).getAsJsonObject())).isNotEqualTo(Runs.of(JsonParser.parseString(other).getAsJsonObject()));
    }

    @Test
    void everyGeneratedResourceParses() {
        for (String type : VectorGenerator.TYPES) {
            String result = String.format("""
                {"runs": {"sotw": {"steps": [[{"envoy": "e", "type": "%s", "resources": [%s]}]]}}}""", type, VectorGenerator.resource(type, "a", 1));
            assertThat(Runs.of(JsonParser.parseString(result).getAsJsonObject())).isNotEmpty();
        }
    }
}
