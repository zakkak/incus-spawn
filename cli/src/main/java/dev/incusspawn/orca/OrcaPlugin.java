package dev.incusspawn.orca;

import dev.incusspawn.util.OutputFormat;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The isx plugin for Orca: an "isx sandbox" recipe in every repository's Run on picker, with no
 * {@code orca.yaml} to edit. It holds no code, only Orca's plugin manifest and the recipe, whose
 * four commands are {@code isx orca}'s; {@code isx orca create} then picks the template itself
 * ({@link OrcaRuntime#chooseTemplate}).
 *
 * <p>The shapes are Orca's ({@code src/shared/plugins/plugin-manifest.ts} and
 * {@code plugin-vm-recipe-artifact.ts} in stablyai/orca), strict like the recipe result. Orca asks
 * the user to approve a plugin again whenever its files change, so they depend on nothing but
 * {@link #VERSION}: not on the isx version, nor on where isx is installed, which Orca finds on the
 * PATH of the user's login shell.
 */
public final class OrcaPlugin {

    private OrcaPlugin() {}

    /** The plugin's version; raise it with any change to what {@link #write} writes. */
    static final String VERSION = "1.0.0";
    /** Orca's plugins can contribute recipes since this release. */
    static final String MIN_ORCA = "1.4.160";
    static final String PUBLISHER = "incus-spawn";
    static final String ID = "isx";
    /** A repository's own orca.yaml recipe of this id takes the plugin's place there. */
    static final String RECIPE_ID = "isx";
    static final String RECIPE_NAME = "isx sandbox";

    static final String MANIFEST = "orca-plugin.json";
    static final String RECIPE = "recipes/" + RECIPE_ID + ".json";
    static final String README = "README.md";

    /** Every file the plugin holds, relative to its directory. */
    static final List<String> FILES = List.of(MANIFEST, RECIPE, README);

    static Map<String, Object> manifest() {
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("manifestVersion", 1);
        manifest.put("id", ID);
        manifest.put("publisher", PUBLISHER);
        manifest.put("name", RECIPE_NAME);
        manifest.put("version", VERSION);
        manifest.put("description", "Runs each workspace in its own incus-spawn (isx) instance, with no credentials in it.");
        manifest.put("repository", "https://github.com/Sanne/incus-spawn");
        manifest.put("engines", Map.of("orca", ">=" + MIN_ORCA));
        manifest.put("pluginApi", 1);
        manifest.put("contributes", Map.of("vmRecipes", List.of(Map.of("path", RECIPE))));
        return manifest;
    }

    static Map<String, Object> recipe() {
        var recipe = new LinkedHashMap<String, Object>();
        recipe.put("schemaVersion", 1);
        recipe.put("id", RECIPE_ID);
        recipe.put("name", RECIPE_NAME);
        recipe.put("description", "A copy-on-write isx instance branched from the template built with the orca tool"
                + " that clones the repository, else the only one.");
        recipe.put("create", "isx orca create");
        recipe.put("suspend", "isx orca suspend");
        recipe.put("resume", "isx orca resume");
        recipe.put("destroy", "isx orca destroy");
        return recipe;
    }

    static String readme() {
        return """
                # isx sandbox for Orca

                This Orca plugin adds an "isx sandbox" option to Run on, for every repository.
                Each workspace then runs in its own incus-spawn (isx) instance.
                `isx orca plugin` wrote it; see https://isx.run/docs/orca for how to install and use it.
                """;
    }

    /**
     * Write the plugin into {@code dir}, replacing one written before. A directory that holds
     * anything else is refused, so a mistyped path cannot mix the plugin into files of the user's.
     */
    public static void write(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var entries = Files.list(dir)) {
                var foreign = entries.map(p -> p.getFileName().toString())
                        .filter(n -> !n.equals(MANIFEST) && !n.equals(README) && !n.equals("recipes"))
                        .sorted().toList();
                if (!foreign.isEmpty() || (Files.exists(dir.resolve("recipes")) && !ownRecipes(dir))) {
                    throw new OrcaRecipe.RecipeException(dir + " holds files that are not the isx plugin's ("
                            + String.join(", ", foreign.isEmpty() ? List.of("recipes") : foreign)
                            + "); give an empty directory or a new one.");
                }
            }
        } else if (Files.exists(dir)) {
            throw new OrcaRecipe.RecipeException(dir + " is not a directory.");
        }
        Files.createDirectories(dir.resolve("recipes"));
        Files.writeString(dir.resolve(MANIFEST), json(manifest()), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(RECIPE), json(recipe()), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(README), readme(), StandardCharsets.UTF_8);
    }

    private static boolean ownRecipes(Path dir) throws IOException {
        try (var recipes = Files.list(dir.resolve("recipes"))) {
            return recipes.allMatch(p -> p.getFileName().toString().equals(RECIPE_ID + ".json"));
        }
    }

    private static String json(Map<String, Object> value) {
        return OutputFormat.toPrettyJson(JqValues.fromJavaObject(value)) + "\n";
    }
}
