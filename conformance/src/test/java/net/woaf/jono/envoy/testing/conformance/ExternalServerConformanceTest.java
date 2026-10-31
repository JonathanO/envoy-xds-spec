package net.woaf.jono.envoy.testing.conformance;

import com.google.common.base.Suppliers;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Runs the suite against a port's conformance server: the binary that system property {@code conformance.server}
 * names. {@code ./gradlew conformance -Pserver=/path/to/server} runs it. docs/CONFORMANCE.md in envoy-xds-spec says
 * what the server does.
 */
@EnabledIfSystemProperty(named = "conformance.server", matches = ".+")
class ExternalServerConformanceTest extends XdsConformanceTest {

    /** Started once, to ask the server its deviations. */
    private final Supplier<Map<String, String>> deviations = Suppliers.memoize(() -> {
        ExternalServer server = ExternalServer.start(System.getProperty("conformance.server"));
        server.close();
        return server.deviations;
    });

    @Override
    protected ControlPlane newControlPlane() {
        return ExternalServer.start(System.getProperty("conformance.server"));
    }

    /**
     * Every release upstream supports, or those {@code -PenvoyVersions=1.36,1.40} names.
     */
    @Override
    protected List<String> envoyImages() {
        String versions = System.getProperty("envoyVersions", "1.36,1.37,1.38,1.39,1.40");
        return Arrays.stream(versions.split(",")).map(v -> "envoyproxy/envoy:v" + v.strip() + "-latest").toList();
    }

    @Override
    protected Map<String, String> knownDeviations() {
        return deviations.get();
    }
}
