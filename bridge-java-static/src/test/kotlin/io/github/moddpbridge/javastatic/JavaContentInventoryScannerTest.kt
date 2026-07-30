package io.github.moddpbridge.javastatic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaContentInventoryScannerTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `inventories SFire-style top-level content without counting nested objects`() {
        val source = temp.resolve("src/example/SFContent.java")
        source.parent.createDirectories()
        source.writeText(
            """
            package example;

            public class SFContent {
                public static Item ore;
                public static Liquid coolant, cells;
                public static StatusEffect charged;
                public static UnitType drone, tank, missile;
                public static Block wall, turret;
                public Item instanceItem;

                public static void load() {
                    ore = new Item("test-" + "ore") {{ hardness = 3; }};
                    coolant = new Liquid("coolant") {{}};
                    cells = new CellLiquid("cells") {{}};
                    charged = new StatusEffect("charged") {{}};
                    drone = new UnitType("drone") {{
                        weapon = new Weapon("nested-weapon") {{
                            bullet = new BasicBulletType();
                            spawnUnit = new MissileUnitType("nested-missile");
                        }};
                    }};
                    tank = new TankUnitType("tank") {{}};
                    missile = new MissileUnitType("missile") {{}};
                    wall = new Wall("wall") {{}};
                    turret = new CustomPowerTurret(name("turret")) {{}};
                    instanceItem = new Item("not-static");
                }
            }
            """.trimIndent(),
        )

        val inventory = JavaContentInventoryScanner().scan(temp.resolve("src"))

        assertEquals(1, inventory.scannedFiles)
        assertTrue(inventory.problems.isEmpty())
        assertEquals(9, inventory.declarations.size)
        assertEquals(
            mapOf(
                JavaContentKind.ITEM to 1,
                JavaContentKind.LIQUID to 2,
                JavaContentKind.STATUS_EFFECT to 1,
                JavaContentKind.UNIT to 3,
                JavaContentKind.BLOCK to 2,
            ),
            inventory.countsByKind,
        )
        assertFalse(inventory.declarations.any { it.contentName == "nested-missile" })
        assertFalse(inventory.declarations.any { it.contentName == "not-static" })

        val item = inventory.declarations.single { it.symbol == "ore" }
        assertEquals("test-ore", item.contentName)
        assertEquals("Item", item.constructedType)
        assertTrue(item.anonymousClassBody)

        val customTurret = inventory.declarations.single { it.symbol == "turret" }
        assertEquals(JavaContentKind.BLOCK, customTurret.kind)
        assertEquals("CustomPowerTurret", customTurret.constructedType)
        assertNull(customTurret.contentName)
        assertEquals("name(\"turret\")", customTurret.nameExpression)
    }

    @Test
    fun `supports direct static field initializers and qualified assignments`() {
        val source = temp.resolve("Direct.java")
        source.writeText(
            """
            class Direct {
                static mindustry.type.Item direct = new mindustry.type.Item("direct");
                static TankUnitType tank;
                static void load() {
                    Direct.tank = (TankUnitType) new TankUnitType("tank");
                }
            }
            """.trimIndent(),
        )

        val inventory = JavaContentInventoryScanner().scan(temp)

        assertEquals(listOf("direct", "tank"), inventory.declarations.map { it.contentName })
        assertEquals(setOf(JavaContentKind.ITEM, JavaContentKind.UNIT), inventory.declarations.map { it.kind }.toSet())
    }

    @Test
    fun `reports malformed source and continues scanning other files`() {
        temp.resolve("Broken.java").writeText("class Broken { static Item x = new Item( ; }")
        temp.resolve("Valid.java").writeText("class Valid { static Item x = new Item(\"x\"); }")

        val inventory = JavaContentInventoryScanner().scan(temp)

        assertEquals(2, inventory.scannedFiles)
        assertEquals(1, inventory.declarations.size)
        assertEquals("x", inventory.declarations.single().contentName)
        assertTrue(inventory.problems.any { it.kind == JavaInventoryProblemKind.PARSE && it.sourcePath == "Broken.java" })
    }
}
