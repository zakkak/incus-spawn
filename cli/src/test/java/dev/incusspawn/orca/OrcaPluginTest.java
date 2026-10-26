package dev.incusspawn.orca;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrcaPluginTest {

    @TempDir
    Path dir;

    @Test
    void theManifestHoldsOnlyWhatOrcasStrictSchemaAccepts() throws Exception {
        OrcaPlugin.write(dir);
        var manifest = JqValues.parseStrict(Files.readString(dir.resolve("orca-plugin.json")));
        assertEquals(List.of("manifestVersion", "id", "publisher", "name", "version", "description", "repository",
                "engines", "pluginApi", "contributes"), keys(manifest));
        assertEquals(1, manifest.pathInt("manifestVersion", 0));
        assertEquals(1, manifest.pathInt("pluginApi", 0));
        // Orca reserves the publisher stablyai and ids starting with orca-
        assertEquals("incus-spawn", manifest.pathText("publisher", ""));
        assertFalse(manifest.pathText("id", "").startsWith("orca-"));
        assertTrue(manifest.pathText("version", "").matches("\\d+\\.\\d+\\.\\d+"));
        assertEquals(">=1.4.160", manifest.path("engines").pathText("orca", ""));
        // Content only: no worker, so no main and no capabilities
        assertEquals(List.of("vmRecipes"), keys(manifest.path("contributes")));
        assertEquals("recipes/isx.json", manifest.path("contributes").path("vmRecipes").asList().getFirst().pathText("path", ""));
    }

    @Test
    void theRecipeIsIsxOrcasFourCommands() throws Exception {
        OrcaPlugin.write(dir);
        var recipe = JqValues.parseStrict(Files.readString(dir.resolve("recipes/isx.json")));
        assertEquals(List.of("schemaVersion", "id", "name", "description", "create", "suspend", "resume", "destroy"),
                keys(recipe));
        assertTrue(recipe.pathText("id", "").matches("[a-z0-9][a-z0-9._-]{0,63}"), "Orca's recipe id rule");
        assertEquals("isx orca create", recipe.pathText("create", ""));
        assertEquals("isx orca suspend", recipe.pathText("suspend", ""));
        assertEquals("isx orca resume", recipe.pathText("resume", ""));
        assertEquals("isx orca destroy", recipe.pathText("destroy", ""));
    }

    @Test
    void writingAgainChangesNothingSoOrcaAsksNoNewApproval() throws Exception {
        OrcaPlugin.write(dir);
        var first = new ArrayList<String>();
        for (var file : OrcaPlugin.FILES) first.add(Files.readString(dir.resolve(file)));
        OrcaPlugin.write(dir);
        for (int i = 0; i < OrcaPlugin.FILES.size(); i++) {
            assertEquals(first.get(i), Files.readString(dir.resolve(OrcaPlugin.FILES.get(i))), OrcaPlugin.FILES.get(i));
        }
        try (var entries = Files.walk(dir)) {
            assertEquals(5, entries.count(), "the directory, recipes/ and the three files, nothing else");
        }
    }

    @Test
    void aDirectoryWithFilesOfItsOwnIsRefused() throws Exception {
        Files.writeString(dir.resolve("notes.txt"), "mine");
        var e = assertThrows(OrcaRecipe.RecipeException.class, () -> OrcaPlugin.write(dir));
        assertTrue(e.getMessage().contains("notes.txt"), e.getMessage());

        var other = Files.createDirectories(dir.resolve("other/recipes"));
        Files.writeString(other.resolve("theirs.json"), "{}");
        assertThrows(OrcaRecipe.RecipeException.class, () -> OrcaPlugin.write(dir.resolve("other")));
    }

    private static List<String> keys(JqValue object) {
        var keys = new ArrayList<String>();
        ((JqObject) object).forEach((key, value) -> keys.add(key));
        return keys;
    }
}
