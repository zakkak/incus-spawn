package dev.incusspawn.orca;

import dev.incusspawn.incus.InstanceNames;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Orca's environment-recipe contract, as {@code isx orca} speaks it: what Orca hands a recipe's
 * {@code create} in its environment, the one JSON object {@code create} and {@code resume} print,
 * and the lifecycle payload {@code suspend}, {@code resume} and {@code destroy} read on stdin.
 *
 * <p>The shapes are Orca's ({@code src/shared/ephemeral-vm-recipes.ts} and
 * {@code ephemeral-vm-recipe-lifecycle-payload.ts} in stablyai/orca). Orca validates them strictly:
 * a field it does not know fails the whole result, so nothing is added here that Orca's schema
 * lacks, and {@code userData} carries what isx needs back.
 */
public final class OrcaRecipe {

    private OrcaRecipe() {}

    /** The user Orca signs in as, whose home every guest path is under. */
    public static final String USER = "agentuser";
    static final String HOME = "/home/" + USER;

    /** Orca adds its own worktree under {@code projectRoot} (no {@code checkoutMode}). */
    public static final int SCHEMA_WORKTREE = 1;
    /** {@code checkoutMode: provisioned-root}: the recipe checks out the workspace's branch itself. */
    public static final int SCHEMA_PROVISIONED_ROOT = 2;
    static final String PROVISIONED_ROOT = "provisioned-root";

    /** The {@code userData.provider} isx stamps on its results, and requires back on a payload. */
    public static final String PROVIDER = "incus-spawn";

    /** How long a runtime id isx stamps may be: Orca's are {@code orca-<uuid>}. */
    static final int MAX_RUNTIME_ID = 200;

    /** The longest workspace part of a name: {@code orca-} + it + {@code -} + the suffix stays well under Incus's 63. */
    static final int MAX_SLUG = 40;
    static final int SUFFIX_LENGTH = 6;
    static final String NAME_PREFIX = "orca-";

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    /** Why a recipe call is refused, as a line for Orca's log. */
    public static final class RecipeException extends RuntimeException {
        public RecipeException(String message) {
            super(message);
        }
    }

    /**
     * What Orca hands {@code create} in its environment ({@code ephemeral-vm-recipe-process.ts}).
     * Run by hand, without Orca, it is a worktree-mode create with a runtime id of its own.
     *
     * @param instanceId    {@code ORCA_VM_INSTANCE_ID}: the runtime the instance is stamped for
     * @param repoPath      {@code ORCA_REPO_PATH}: Orca's checkout on this host
     * @param repoUrl       {@code ORCA_REPO_URL}: set only for {@code provisioned-root}, as are the three after it
     * @param schemaVersion {@code ORCA_RECIPE_RESULT_SCHEMA_VERSION}: which result Orca will accept
     */
    public record Context(String instanceId, String workspaceName, Path repoPath, String repoUrl,
                          String repoRef, String refHead, String branch, int schemaVersion) {

        public boolean provisionedRoot() {
            return schemaVersion == SCHEMA_PROVISIONED_ROOT;
        }

        public static Context fromEnv(Map<String, String> env, Path cwd) {
            var schema = env.getOrDefault("ORCA_RECIPE_RESULT_SCHEMA_VERSION", "").strip();
            int schemaVersion;
            if (schema.isEmpty()) {
                schemaVersion = SCHEMA_WORKTREE;
            } else if (schema.equals("1") || schema.equals("2")) {
                schemaVersion = Integer.parseInt(schema);
            } else {
                throw new RecipeException("Orca asked for recipe result schema " + oneLine(schema)
                        + ", which this isx does not write; update isx.");
            }
            var instanceId = env.getOrDefault("ORCA_VM_INSTANCE_ID", "").strip();
            if (instanceId.isEmpty()) instanceId = "isx-" + UUID.randomUUID();
            if (instanceId.length() > MAX_RUNTIME_ID || hasControl(instanceId)) {
                throw new RecipeException("ORCA_VM_INSTANCE_ID is not a runtime id isx can stamp.");
            }
            var repoPath = env.getOrDefault("ORCA_REPO_PATH", "").strip();
            var context = new Context(instanceId, env.getOrDefault("ORCA_WORKSPACE_NAME", "").strip(),
                    repoPath.isEmpty() ? cwd : Path.of(repoPath), blankToNull(env.get("ORCA_REPO_URL")),
                    blankToNull(env.get("ORCA_REPO_REF")), blankToNull(env.get("ORCA_REPO_REF_HEAD")),
                    blankToNull(env.get("ORCA_REPO_BRANCH")), schemaVersion);
            if (context.provisionedRoot()) context.requireProvisionedSource();
            return context;
        }

        /** The pinned source a provisioned root checks out, which Orca always sends for one. */
        private void requireProvisionedSource() {
            if (repoUrl == null || repoRef == null || refHead == null || branch == null) {
                throw new RecipeException("a provisioned-root create needs ORCA_REPO_URL, ORCA_REPO_REF,"
                        + " ORCA_REPO_REF_HEAD and ORCA_REPO_BRANCH from Orca.");
            }
            if (!COMMIT.matcher(refHead).matches()) {
                throw new RecipeException("ORCA_REPO_REF_HEAD is not a commit id.");
            }
            if (refName(repoRef) == null || refName(branch) == null) {
                throw new RecipeException("ORCA_REPO_REF or ORCA_REPO_BRANCH is not a Git ref name.");
            }
        }
    }

    /**
     * The instance a runtime gets: {@code orca-<workspace>-<suffix>}. The suffix comes from the
     * runtime id, so two workspaces of one name differ, and a create repeated for one runtime asks
     * for the name it already has rather than a second instance.
     */
    public static String instanceName(String workspaceName, String instanceId) {
        var slug = slug(workspaceName);
        var name = NAME_PREFIX + (slug.isEmpty() ? "" : slug + "-") + suffix(instanceId);
        if (!InstanceNames.isValid(name)) throw new IllegalStateException("not an instance name: " + name);
        return name;
    }

    static String slug(String text) {
        if (text == null) return "";
        var slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_SLUG) slug = slug.substring(0, MAX_SLUG).replaceAll("-+$", "");
        return slug;
    }

    static String suffix(String instanceId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(instanceId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, SUFFIX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The URL the instance clones {@code url} from: HTTPS, which the isx proxy signs in to, with
     * no user or password. An SSH or {@code git://} remote becomes the same path over HTTPS, since
     * the instance holds no SSH key; a local path, {@code file://} or a remote helper is refused,
     * as nothing on this host is the instance's to read.
     */
    public static String guestCloneUrl(String url) {
        if (url == null || url.isBlank()) throw new RecipeException("no repository URL to clone.");
        var s = url.strip();
        if (hasControl(s) || s.chars().anyMatch(Character::isWhitespace)) throw notClonable(url);
        var scheme = s.indexOf("://");
        if (scheme < 0) {
            // scp-like [user@]host:path, which git reads only when no slash comes before the colon
            var colon = s.indexOf(':');
            var slash = s.indexOf('/');
            if (colon > 0 && (slash < 0 || colon < slash) && !s.contains("::")) {
                var host = s.substring(s.lastIndexOf('@', colon) + 1, colon);
                var path = s.substring(colon + 1);
                if (path.startsWith("~")) throw notClonable(url);
                return https(host, path.startsWith("/") ? path : "/" + path, url);
            }
            throw notClonable(url);
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            throw notClonable(url);
        }
        var protocol = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        return switch (protocol) {
            // Over HTTP(S) the port is the web server's, which the clone keeps
            case "https", "http" -> {
                if (uri.getRawPath() == null) throw notClonable(url);
                var host = uri.getHost() == null ? null : uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
                yield protocol.equals("http") ? "http://" + https(host, uri.getRawPath(), url).substring(8)
                        : https(host, uri.getRawPath(), url);
            }
            // An SSH port is the SSH server's, which says nothing of where HTTPS listens
            case "ssh", "git", "git+ssh", "ssh+git" -> {
                if (uri.getRawPath() == null || uri.getRawPath().startsWith("/~")) throw notClonable(url);
                yield https(uri.getHost(), uri.getRawPath(), url);
            }
            default -> throw notClonable(url);
        };
    }

    private static String https(String host, String path, String original) {
        if (host == null || host.isBlank() || path == null || path.isBlank() || path.equals("/")) {
            throw notClonable(original);
        }
        return "https://" + host + path;
    }

    private static RecipeException notClonable(String url) {
        return new RecipeException("cannot clone '" + oneLine(stripUserInfo(url)) + "' in an isx instance:"
                + " it takes an HTTPS, SSH or git:// remote. Add the repository to the template's repos,"
                + " or give --project-root.");
    }

    /** {@code url} without a user or password, for a message: a token in a remote URL must not reach Orca's log. */
    static String stripUserInfo(String url) {
        return url == null ? "" : url.replaceFirst("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@]*@", "$1");
    }

    /** How Orca reaches the instance: the isx Host alias, its key, and the exec tunnel the alias uses. */
    public record Target(String name, String identityFile, String proxyCommand) {}

    /**
     * The result {@code create} and {@code resume} print. The target spells out the identity and
     * ProxyCommand the instance's Host block gives, because Orca hands a recipe's target to ssh
     * itself rather than by its alias; ssh still reads the block for the host key settings.
     */
    public static Map<String, Object> result(int schemaVersion, String projectRoot, Target target, String runtimeId) {
        var targetFields = new LinkedHashMap<String, Object>();
        targetFields.put("label", target.name());
        targetFields.put("host", target.name());
        targetFields.put("configHost", target.name());
        targetFields.put("port", 22);
        targetFields.put("username", USER);
        targetFields.put("identityFile", target.identityFile());
        targetFields.put("identitiesOnly", true);
        targetFields.put("proxyCommand", target.proxyCommand());
        var connection = new LinkedHashMap<String, Object>();
        connection.put("type", "ssh");
        connection.put("projectRoot", projectRoot);
        connection.put("target", targetFields);
        var userData = new LinkedHashMap<String, Object>();
        userData.put("provider", PROVIDER);
        userData.put("resourceId", target.name());
        userData.put("runtimeId", runtimeId);
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", schemaVersion);
        if (schemaVersion == SCHEMA_PROVISIONED_ROOT) result.put("checkoutMode", PROVISIONED_ROOT);
        result.put("connection", connection);
        result.put("userData", userData);
        return result;
    }

    public static String toJson(Map<String, Object> result) {
        return JqValues.fromJavaObject(result).toJsonString();
    }

    /**
     * What a lifecycle payload names: the instance, the runtime it must be stamped for, and what
     * {@code resume} prints back.
     */
    public record Lifecycle(String mode, String instance, String runtimeId, String projectRoot, int schemaVersion) {}

    /**
     * The payload Orca writes to {@code suspend}, {@code resume} and {@code destroy} on stdin,
     * refused unless it is one isx's {@code create} answered and {@code expectedMode}'s.
     */
    public static Lifecycle parsePayload(String text, String expectedMode) {
        JqValue payload;
        try {
            payload = JqValues.parseStrict(text == null ? "" : text.strip());
        } catch (RuntimeException e) {
            throw new RecipeException("expected Orca's lifecycle payload, one JSON object, on stdin.");
        }
        if (!(payload instanceof JqObject)) {
            throw new RecipeException("expected Orca's lifecycle payload, one JSON object, on stdin.");
        }
        var mode = payload.pathText("mode", "");
        if (!expectedMode.equals(mode)) {
            throw new RecipeException("the payload is for " + (mode.isEmpty() ? "no mode" : "'" + oneLine(mode) + "'")
                    + ", not " + expectedMode + ": check the recipe's " + expectedMode + " command.");
        }
        var result = payload.path("recipeResult");
        var userData = result.path("userData");
        if (!PROVIDER.equals(userData.pathText("provider", ""))) {
            throw new RecipeException("the payload's recipe result is not one isx orca create printed.");
        }
        var instance = userData.pathText("resourceId", "");
        if (!InstanceNames.isValid(instance)) {
            throw new RecipeException("the payload names no isx instance.");
        }
        var runtimeId = payload.pathText("instanceId", "");
        if (runtimeId.isEmpty()) runtimeId = userData.pathText("runtimeId", "");
        if (runtimeId.isEmpty()) throw new RecipeException("the payload names no Orca runtime.");
        var projectRoot = result.path("connection").pathText("projectRoot", "");
        return new Lifecycle(mode, instance, runtimeId, projectRoot,
                result.pathInt("schemaVersion", SCHEMA_WORKTREE));
    }

    /** {@code ref} if Git could take it as a ref name on a command line, else null. */
    static String refName(String ref) {
        if (ref == null || ref.isEmpty() || ref.startsWith("-") || hasControl(ref)
                || ref.chars().anyMatch(c -> c == ' ' || c == '~' || c == '^' || c == ':' || c == '?'
                || c == '*' || c == '[' || c == '\\')
                || ref.contains("..") || ref.contains("@{") || ref.endsWith(".lock") || ref.endsWith("/")) {
            return null;
        }
        return ref;
    }

    static boolean hasControl(String text) {
        return text.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String oneLine(String text) {
        return dev.incusspawn.util.OutputFormat.oneLine(text);
    }
}
