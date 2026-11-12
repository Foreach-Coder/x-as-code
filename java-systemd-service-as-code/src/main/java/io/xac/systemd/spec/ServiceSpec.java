package io.xac.systemd.spec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ServiceSpec {

    public static final class Builder {
        private final Path packageDir;
        private String name;
        private String description;
        private String jar;
        private String java;
        private List<String> opts;
        private Map<String, String> env;
        private Map<String, String> properties;
        private String workdir;
        private List<String> pathLinks;
        private String logFile;
        private Lifecycle lifecycle = new Lifecycle(null, null, null, null);

        public Builder(Path packageDir) {
            this.packageDir = packageDir;
        }

        public Builder name(String name) { this.name = name; return this; }

        public Builder description(String description) { this.description = description; return this; }

        public Builder jar(String jar) { this.jar = jar; return this; }

        public Builder java(String java) { this.java = java; return this; }

        public Builder opts(List<String> opts) { this.opts = opts; return this; }

        public Builder env(Map<String, String> env) { this.env = env; return this; }

        public Builder properties(Map<String, String> properties) { this.properties = properties; return this; }

        public Builder workdir(String workdir) { this.workdir = workdir; return this; }

        public Builder pathLinks(List<String> pathLinks) { this.pathLinks = pathLinks; return this; }

        public Builder logFile(String logFile) { this.logFile = logFile; return this; }

        public Builder lifecycle(Lifecycle lifecycle) { this.lifecycle = lifecycle; return this; }

        public ServiceSpec build() {
            return new ServiceSpec(this);
        }
    }

    public final String name;
    public final String description;
    public final Path jar;
    public final String java;
    public final List<String> opts;
    public final LinkedHashMap<String, String> env;
    public final LinkedHashMap<String, String> properties;
    public final Path workdir;
    public final List<String> pathLinks;
    public final String logFile;
    public final Lifecycle lifecycle;
    public final Path packageDir;

    private ServiceSpec(Builder b) {
        this.name = b.name;
        this.description = b.description;
        Path root = b.packageDir.toAbsolutePath().normalize();
        this.jar = b.jar == null ? null : root.resolve(b.jar).normalize();
        this.java = b.java;
        this.opts = b.opts == null ? new ArrayList<String>() : b.opts;
        this.env = b.env == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(b.env);
        this.properties = b.properties == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(b.properties);
        this.workdir = b.workdir == null ? null : root.resolve(b.workdir).normalize();
        this.pathLinks = b.pathLinks == null ? new ArrayList<String>() : b.pathLinks;
        this.logFile = b.logFile;
        this.lifecycle = b.lifecycle;
        this.packageDir = root;
    }

    public static Builder builder(Path packageDir) {
        return new Builder(packageDir);
    }
}
