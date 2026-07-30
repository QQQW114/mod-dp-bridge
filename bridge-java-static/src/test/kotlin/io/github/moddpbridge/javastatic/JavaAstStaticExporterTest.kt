package io.github.moddpbridge.javastatic

import io.github.moddpbridge.converter.DetectedSourceKind
import io.github.moddpbridge.converter.StaticExportContext
import io.github.moddpbridge.converter.StaticExportResult
import io.github.moddpbridge.converter.StaticSourceFile
import io.github.moddpbridge.model.ContentDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaAstStaticExporterTest {
    @Test
    fun `exports item liquid and status expressions without executing Java`() {
        val result = exportJava(
            "src/FixtureContent.java" to
                """
                import static mindustry.content.StatusEffects.burning;

                class FixtureContent {
                    static final Color fixtureColor = Color.valueOf("AbCdEf");

                    static Item alloy;
                    static Liquid acid;
                    static StatusEffect haze;

                    static void load() {
                        alloy = new Item("alloy", fixtureColor) {{
                            cost = 1f + 2f;
                            hardness = 4;
                        }};

                        acid = new CellLiquid("acid", Items.lead.color) {{
                            viscosity = 0.8f;
                            effect = burning;
                        }};

                        haze = new StatusEffect("haze") {{
                            speedMultiplier = reloadMultiplier = 0.5f;
                            effect = new MultiEffect(
                                new ParticleEffect() {{
                                    particles = 4;
                                    colorFrom = fixtureColor;
                                }},
                                new WaveEffect() {{
                                    sizeTo = 12f;
                                }}
                            );
                        }};
                    }
                }
                """.trimIndent(),
        )

        assertEquals(3, result.generatedFiles.size)
        assertEquals(3, result.contentResults.size)
        assertTrue(result.contentResults.all { it.disposition == ContentDisposition.CONVERTED })

        val item = result.generatedJson("content/items/alloy.hjson")
        assertContainsJson(item, "\"color\":\"abcdefff\"")
        assertContainsJson(item, "\"cost\":3")
        assertContainsJson(item, "\"hardness\":4")

        val liquid = result.generatedJson("content/liquids/acid.hjson")
        assertContainsJson(liquid, "\"type\":\"CellLiquid\"")
        assertContainsJson(liquid, "\"color\":\"8c7fa9ff\"")
        assertContainsJson(liquid, "\"viscosity\":0.8")
        assertContainsJson(liquid, "\"effect\":\"burning\"")

        val status = result.generatedJson("content/statuses/haze.hjson")
        assertContainsJson(status, "\"speedMultiplier\":0.5")
        assertContainsJson(status, "\"reloadMultiplier\":0.5")
        // ContentParser treats an Effect-valued array as a MultiEffect, so the compact and
        // loadable DP representation intentionally does not need a {type,effects} wrapper.
        assertContainsJson(status, "\"effect\":[{\"type\":\"ParticleEffect\"")
        assertContainsJson(status, "\"colorFrom\":\"abcdefff\"")
        assertContainsJson(status, "{\"type\":\"WaveEffect\",\"sizeTo\":12}")
        assertFalse(result.diagnostics.any { it.code == "JAVA_FIELD_EXPRESSION_OMITTED" })
    }

    @Test
    fun `retains status relations but reports callbacks and method overrides as degraded`() {
        val result = exportJava(
            "src/StatusContent.java" to
                """
                import static mindustry.content.StatusEffects.burning;
                import static mindustry.content.StatusEffects.freezing;
                import static mindustry.content.StatusEffects.tarred;

                class StatusContent {
                    static StatusEffect unstable = new StatusEffect("unstable") {
                        {
                            opposite(burning, freezing);
                            affinity(tarred, (unit, result, time) -> result.set(unstable, time));
                        }

                        @Override
                        public void update(Unit unit, float time) {
                            unit.damageContinuousPierce(1f);
                        }
                    };
                }
                """.trimIndent(),
        )

        val content = result.contentResults.single()
        assertEquals(ContentDisposition.DEGRADED, content.disposition)
        assertTrue("JAVA_STATUS_OPPOSITE_DISPLAY_ONLY" in content.diagnosticCodes)
        assertTrue("JAVA_STATUS_CALLBACK_OMITTED" in content.diagnosticCodes)
        assertTrue("JAVA_METHOD_OVERRIDE_OMITTED" in content.diagnosticCodes)

        val status = result.generatedJson("content/statuses/unstable.hjson")
        assertContainsJson(status, "\"opposites\":[\"burning\",\"freezing\"]")
        assertContainsJson(status, "\"affinities\":[\"tarred\"]")
        assertFalse(status.contains("damageContinuousPierce"))
    }

    @Test
    fun `lowers block requirements consumers and ammo map`() {
        val result = exportJava(
            "src/BlockContent.java" to
                """
                class BlockContent {
                    static Item alloy = new Item("alloy");

                    static Block fixture = new ItemTurret("fixture") {{
                        requirements(
                            Category.turret,
                            BuildVisibility.shown,
                            ItemStack.with(Items.copper, 30, alloy, 4)
                        );
                        consumePower(2.5f);
                        consumeItem(Items.graphite, 2);
                        consumeLiquid(Liquids.water, 0.1f);
                        ammo(
                            Items.copper, new BasicBulletType(3f, 12f) {{
                                width = 5f;
                            }},
                            alloy, new MissileBulletType(2f, 20f) {{
                                lifetime = 60f;
                            }}
                        );
                    }};
                }
                """.trimIndent(),
        )

        val blockResult = result.contentResults.single { it.outputPath == "content/blocks/fixture.hjson" }
        assertEquals(ContentDisposition.CONVERTED, blockResult.disposition)

        val block = result.generatedJson("content/blocks/fixture.hjson")
        assertContainsJson(block, "\"type\":\"ItemTurret\"")
        assertContainsJson(block, "\"category\":\"turret\"")
        assertContainsJson(block, "\"buildVisibility\":\"shown\"")
        assertContainsJson(block, "\"requirements\"")
        assertTrue(block.contains("copper") && block.contains("30"), block)
        assertTrue(block.contains("alloy") && block.contains("4"), block)

        assertContainsJson(block, "\"consumes\"")
        assertContainsJson(block, "\"power\":2.5")
        assertTrue(block.contains("graphite") && block.contains("2"), block)
        assertTrue(block.contains("water") && block.contains("0.1"), block)

        assertContainsJson(block, "\"ammoTypes\":{")
        assertContainsJson(block, "\"copper\":{\"type\":\"BasicBulletType\"")
        assertContainsJson(block, "\"speed\":3")
        assertContainsJson(block, "\"damage\":12")
        assertContainsJson(block, "\"width\":5")
        assertTrue(block.contains("alloy") && block.contains("MissileBulletType"), block)
        assertContainsJson(block, "\"lifetime\":60")
    }

    @Test
    fun `lowers unit template entity constructor weapons and abilities`() {
        val result = exportJava(
            "src/UnitContent.java" to
                """
                class UnitContent {
                    static UnitType drone = new TankUnitType("drone") {{
                        constructor = TankUnit::create;
                        speed = 0.8f;
                        weapons.add(new Weapon("drone-gun") {{
                            x = 4f;
                            reload = 20f;
                            bullet = new BasicBulletType(4f, 10f) {{
                                lifetime = 50f;
                            }};
                        }});
                        abilities.add(new ForceFieldAbility(20f, 1.5f, 200f, 120f));
                    }};

                    static UnitType skimmer = new UnitType("skimmer") {{
                        constructor = UnitTypes.elude.constructor;
                    }};
                }
                """.trimIndent(),
        )

        val droneResult = result.contentResults.single { it.outputPath == "content/units/drone.hjson" }
        val skimmerResult = result.contentResults.single { it.outputPath == "content/units/skimmer.hjson" }
        assertEquals(ContentDisposition.CONVERTED, droneResult.disposition)
        assertEquals(ContentDisposition.CONVERTED, skimmerResult.disposition)

        val drone = result.generatedJson("content/units/drone.hjson")
        assertContainsJson(drone, "\"template\":\"TankUnitType\"")
        assertContainsJson(drone, "\"type\":\"tank\"")
        assertContainsJson(drone, "\"speed\":0.8")
        assertContainsJson(drone, "\"weapons\":[{")
        assertContainsJson(drone, "\"name\":\"drone-gun\"")
        assertContainsJson(drone, "\"bullet\":{\"type\":\"BasicBulletType\"")
        assertContainsJson(drone, "\"damage\":10")
        assertContainsJson(drone, "\"abilities\":[{\"type\":\"ForceFieldAbility\"")
        assertContainsJson(drone, "\"radius\":20")
        assertContainsJson(drone, "\"regen\":1.5")
        assertContainsJson(drone, "\"max\":200")
        assertContainsJson(drone, "\"cooldown\":120")

        val skimmer = result.generatedJson("content/units/skimmer.hjson")
        assertContainsJson(skimmer, "\"type\":\"hover\"")
        assertFalse(skimmer.contains("constructor"), skimmer)
    }

    @Test
    fun `preserves both OreBlock constructor overload semantics`() {
        val result = exportJava(
            "src/OreContent.java" to
                """
                class OreContent {
                    static Block coalOre = new OreBlock(Items.coal) {{
                        variants = 3;
                    }};

                    static Block deepCoal = new OreBlock("deep-coal", Items.coal) {{
                        variants = 2;
                    }};
                }
                """.trimIndent(),
        )

        val derived = result.generatedJson("content/blocks/ore-coal.hjson")
        assertContainsJson(derived, "\"type\":\"OreBlock\"")
        assertContainsJson(derived, "\"itemDrop\":\"coal\"")
        assertContainsJson(derived, "\"variants\":3")

        val explicit = result.generatedJson("content/blocks/deep-coal.hjson")
        assertContainsJson(explicit, "\"type\":\"OreBlock\"")
        assertContainsJson(explicit, "\"itemDrop\":\"coal\"")
        assertContainsJson(explicit, "\"variants\":2")

        assertTrue(result.contentResults.all { it.disposition == ContentDisposition.CONVERTED })
        assertFalse(
            result.diagnostics.any {
                it.code == "JAVA_CONSTRUCTOR_ARGUMENT_OMITTED" || it.code == "JAVA_FIELD_EXPRESSION_OMITTED"
            },
        )
    }

    @Test
    fun `uses data-pack content keys and loadable boosted liquid color and null effect values`() {
        val result = exportJava(
            "src/ParserEdgeContent.java" to
                """
                class ParserEdgeContent {
                    static Item alloy;
                    static StatusEffect quiet;
                    static Block reactor;

                    static void load() {
                        alloy = new Item("alloy", new Color());

                        quiet = new StatusEffect("quiet") {{
                            effect = null;
                        }};

                        reactor = new ConsumeGenerator("reactor") {{
                            itemDurationMultipliers.put(Items.thorium, 0.5f);
                            itemDurationMultipliers.put(alloy, 1.25f);
                            consumeLiquid(Liquids.water, 0.2f).boost();
                        }};
                    }
                }
                """.trimIndent(),
        )

        val item = result.generatedJson("content/items/alloy.hjson")
        assertContainsJson(item, "\"color\":\"ffffffff\"")

        val status = result.generatedJson("content/statuses/quiet.hjson")
        assertContainsJson(status, "\"effect\":\"none\"")
        assertFalse(status.contains("\"effect\":null"), status)

        val reactor = result.generatedJson("content/blocks/reactor.hjson")
        assertContainsJson(reactor, "\"itemDurationMultipliers\":{\"thorium\":0.5,\"dp-alloy\":1.25}")
        assertContainsJson(reactor, "\"liquidsBoost\":[\"water/0.2\"]")
    }

    @Test
    fun `keeps reconstructor upgrades two dimensional and resolves method local plan requirements`() {
        val result = exportJava(
            "src/FactoryContent.java" to
                """
                class FactoryContent {
                    static Item alloy = new Item("alloy");
                    static UnitType worker = new UnitType("worker");
                    static Block factory;
                    static Block reconstructor;
                    static Block baseWall;
                    static Block largeWall;
                    static Block assembler;

                    static void load() {
                        ItemStack[] tier = ItemStack.with(Items.lead, 30, alloy, 2);

                        baseWall = new Wall("base-wall") {{
                            requirements(Category.defense, ItemStack.with(Items.lead, 5, alloy, 2));
                        }};

                        largeWall = new Wall("large-wall") {{
                            requirements(Category.defense, ItemStack.mult(baseWall.requirements, 4));
                        }};

                        factory = new UnitFactory("factory") {{
                            plans = Seq.with(new UnitPlan(worker, 60f, tier));
                        }};

                        assembler = new UnitAssembler("assembler") {{
                            plans.add(new AssemblerUnitPlan(
                                worker, 120f, PayloadStack.list(Blocks.router, 2, worker, 3)
                            ));
                        }};

                        reconstructor = new Reconstructor("reconstructor") {{
                            upgrades.addAll(
                                new UnitType[]{UnitTypes.dagger, worker},
                                new UnitType[]{worker, UnitTypes.mace}
                            );
                        }};
                    }
                }
                """.trimIndent(),
        )

        val factory = result.generatedJson("content/blocks/factory.hjson")
        assertContainsJson(factory, "\"plans\":[{\"unit\":\"dp-worker\",\"time\":60,\"requirements\":[\"lead/30\",\"dp-alloy/2\"]}]")
        assertFalse(result.diagnostics.any { diagnostic ->
            diagnostic.code == "JAVA_FIELD_EXPRESSION_OMITTED" && diagnostic.location?.path == "src/FactoryContent.java"
        })

        val reconstructor = result.generatedJson("content/blocks/reconstructor.hjson")
        assertContainsJson(
            reconstructor,
            "\"upgrades\":[[\"dagger\",\"dp-worker\"],[\"dp-worker\",\"mace\"]]",
        )

        val largeWall = result.generatedJson("content/blocks/large-wall.hjson")
        assertContainsJson(largeWall, "\"requirements\":[\"lead/20\",\"dp-alloy/8\"]")

        val assembler = result.generatedJson("content/blocks/assembler.hjson")
        assertContainsJson(
            assembler,
            "\"requirements\":[\"router/2\",\"dp-worker/3\"]",
        )
    }

    @Test
    fun `maps Java aiController to the data patch controller field`() {
        val result = exportJava(
            "src/ControllerContent.java" to
                """
                class ControllerContent {
                    static UnitType worker = new UnitType("worker") {{
                        aiController = BuilderAI::new;
                        defaultController = DefenderAI::new;
                        defaultCommand = UnitCommand.rebuildCommand;
                    }};
                }
                """.trimIndent(),
        )

        val unit = result.generatedJson("content/units/worker.hjson")
        assertContainsJson(unit, "\"controller\":\"BuilderAI\"")
        assertContainsJson(unit, "\"defaultController\":\"DefenderAI\"")
        assertContainsJson(unit, "\"defaultCommand\":\"rebuild\"")
        assertFalse(unit.contains("\"aiController\""), unit)
    }

    @Test
    fun `maps v159 positional constructors and removes fields rejected by DataPatcher`() {
        val result = exportJava(
            "src/TargetMappings.java" to
                """
                class TargetMappings {
                    static Block turret = new PowerTurret("turret") {{
                        shoot = new ShootMulti(
                            new ShootPattern() {{ shots = 4; }},
                            new ShootHelix(4f, 3f),
                            new ShootSpread(2, 6f)
                        );
                        drawer = new DrawTurret("reinforced-") {{
                            parts.add(new RegionPart("-glow", Blending.additive, Color.red) {{
                                top = true;
                                mixColor = new Color(1f, 1f, 1f, 0f);
                                progress = PartProgress.recoil.curve(Interp.swingOut).inv();
                                moveX = 2f;
                                moveY = -moveX;
                                moves.add(new PartMove(PartProgress.recoil.loop(30f), 1f, 2f, 45f));
                                moves.add(new PartMove(PartProgress.charge, 1f, 2f, 3f, 4f, 90f));
                            }});
                        }};
                        shootType = new ExplosionBulletType(220f, 50f);
                    }};

                    static UnitType unit = new UnitType("unit") {{
                        abilities.add(new ForceFieldAbility(20f, 1f, 200f, 120f, 8, 15f));
                        weapons.add(new Weapon("fire") {{
                            bullet = new FireBulletType(4f, 18f) {{
                                backColor = Color.red;
                                trailColor = backColor;
                                splashDamage = damage * 0.75f;
                            }};
                        }});
                    }};
                }
                """.trimIndent(),
        )

        val turret = result.generatedJson("content/blocks/turret.hjson")
        assertContainsJson(turret, "\"shoot\":{\"type\":\"ShootMulti\"")
        assertContainsJson(turret, "\"source\":{\"type\":\"ShootPattern\",\"shots\":4}")
        assertContainsJson(turret, "\"dest\":[{\"type\":\"ShootHelix\",\"scl\":4,\"mag\":3}")
        assertContainsJson(turret, "{\"type\":\"ShootSpread\",\"shots\":2,\"spread\":6}]")
        assertContainsJson(turret, "\"basePrefix\":\"reinforced-\"")
        assertContainsJson(turret, "\"suffix\":\"-glow\"")
        assertContainsJson(turret, "\"blending\":\"additive\"")
        assertContainsJson(turret, "\"color\":\"ff0000ff\"")
        assertContainsJson(turret, "\"mixColor\":\"ffffff00\"")
        assertContainsJson(
            turret,
            "\"progress\":{\"type\":\"recoil\",\"operations\":[{\"operation\":\"curve\",\"interp\":\"swingOut\"},{\"operation\":\"inv\"}]}",
        )
        assertContainsJson(turret, "\"moveX\":2,\"moveY\":-2")
        assertContainsJson(
            turret,
            "\"moves\":[{\"progress\":{\"type\":\"recoil\",\"operations\":[{\"operation\":\"loop\",\"time\":30}]},\"x\":1,\"y\":2,\"rot\":45}",
        )
        assertContainsJson(turret, "{\"progress\":\"charge\",\"x\":1,\"y\":2,\"gx\":3,\"gy\":4,\"rot\":90}")
        assertFalse(turret.contains("\"top\""), turret)
        assertContainsJson(turret, "\"splashDamage\":220")
        assertContainsJson(turret, "\"splashDamageRadius\":50")

        val unit = result.generatedJson("content/units/unit.hjson")
        assertContainsJson(unit, "\"sides\":8")
        assertContainsJson(unit, "\"rotation\":15")
        assertContainsJson(unit, "\"bullet\":{\"type\":\"FireBulletType\",\"speed\":4,\"damage\":18")
        assertContainsJson(unit, "\"backColor\":\"ff0000ff\",\"trailColor\":\"ff0000ff\",\"splashDamage\":13.5")
        assertTrue(result.diagnostics.any { it.code == "JAVA_TARGET_FIELD_OMITTED" })
    }

    @Test
    fun `uses MissileBulletType color defaults when resolving inherited field references`() {
        val result = exportJava(
            "src/MissileDefaults.java" to
                """
                class MissileDefaults {
                    static UnitType unit = new UnitType("unit") {{
                        weapons.add(new Weapon("missile") {{
                            bullet = new MissileBulletType(4f, 20f) {{
                                lightningColor = backColor;
                                trailColor = frontColor;
                            }};
                        }});
                    }};
                }
                """.trimIndent(),
        )

        val unit = result.generatedJson("content/units/unit.hjson")
        assertContainsJson(unit, "\"backColor\":\"e58956ff\",\"frontColor\":\"ffd2aeff\"")
        assertContainsJson(unit, "\"lightningColor\":\"e58956ff\",\"trailColor\":\"ffd2aeff\"")
    }

    @Test
    fun `binds no argument DrawLiquidTile to output liquid or degrades the layer safely`() {
        val result = exportJava(
            "src/LiquidDrawers.java" to
                """
                class LiquidDrawers {
                    static Block inferred = new GenericCrafter("inferred") {{
                        drawer = new DrawMulti(new DrawLiquidTile(), new DrawDefault());
                        outputLiquid = new LiquidStack(Liquids.slag, 1f);
                    }};

                    static Block safeFallback = new GenericCrafter("safe-fallback") {{
                        drawer = new DrawMulti(
                            new DrawRegion("-spin", 3f, true),
                            new DrawLiquidTile(),
                            new DrawDefault()
                        );
                    }};
                }
                """.trimIndent(),
        )

        val inferred = result.generatedJson("content/blocks/inferred.hjson")
        assertContainsJson(inferred, "\"type\":\"DrawLiquidTile\",\"drawLiquid\":\"slag\"")

        val fallback = result.generatedJson("content/blocks/safe-fallback.hjson")
        assertContainsJson(fallback, "\"type\":\"DrawRegion\",\"suffix\":\"-spin\",\"rotateSpeed\":3,\"spinSprite\":true")
        assertFalse(fallback.contains("DrawLiquidTile"), fallback)
        assertEquals(
            ContentDisposition.DEGRADED,
            result.contentResults.single { it.outputPath == "content/blocks/safe-fallback.hjson" }.disposition,
        )
        assertTrue(result.diagnostics.any { it.code == "JAVA_DRAW_LIQUID_TILE_DEGRADED" })
    }

    @Test
    fun `keeps loadable effect graphs around custom factories and fluent modifiers`() {
        val result = exportJava(
            "src/EffectFallbacks.java" to
                """
                class EffectFallbacks {
                    static StatusEffect effectStatus = new StatusEffect("effect-status") {{
                        effect = new MultiEffect(
                            Fx.mineImpactWave.wrap(Items.blastCompound.color, 45f),
                            SFFx.customExplosion(12f),
                            new Effect(20f, e -> {}).layer(Layer.groundUnit - 1f)
                        );
                    }};

                    static Block cooled = new PowerTurret("cooled") {{
                        coolant = consumeCoolant(0.1f);
                    }};
                }
                """.trimIndent(),
        )

        val status = result.generatedJson("content/statuses/effect-status.hjson")
        assertContainsJson(
            status,
            "\"effect\":[{\"type\":\"WrapEffect\",\"effect\":\"mineImpactWave\",\"color\":\"ff795eff\",\"rotation\":45},\"none\",\"none\"]",
        )
        assertTrue(result.diagnostics.any { it.code == "JAVA_CUSTOM_EFFECT_REFERENCE_OMITTED" })

        val cooled = result.generatedJson("content/blocks/cooled.hjson")
        assertContainsJson(cooled, "\"consumes\":{\"coolant\":{\"amount\":0.1}}")
        assertFalse(
            result.diagnostics.any {
                it.code == "JAVA_FIELD_EXPRESSION_OMITTED" && it.message.contains("'coolant'")
            },
        )
    }

    @Test
    fun `resolves initializer locals and deterministic weapon copy helpers`() {
        val result = exportJava(
            "src/LocalCopies.java" to
                """
                class LocalCopies {
                    static UnitType localCopies = new UnitType("local-copies") {{
                        float damageScale = 1.5f;
                        BulletType shared = new BasicBulletType(4f, 20f * damageScale) {{
                            float radius = damage;
                            splashDamageRadius = radius;
                        }};
                        Weapon base = new Weapon("base") {{
                            reload = 10f;
                            bullet = shared;
                        }};
                        weapons.addAll(
                            copy(base, 2f, 3f),
                            copyRotate(base, 4f, 5f, 90f),
                            copyRotRel(base, 6f, 7f, -45f, 2f)
                        );
                    }};
                }
                """.trimIndent(),
        )

        val unit = result.generatedJson("content/units/local-copies.hjson")
        assertContainsJson(unit, "\"damage\":30,\"splashDamageRadius\":30")
        assertContainsJson(unit, "\"x\":2,\"y\":3")
        assertContainsJson(unit, "\"x\":4,\"y\":5,\"baseRotation\":90")
        assertContainsJson(unit, "\"reload\":12")
        assertContainsJson(unit, "\"x\":6,\"y\":7,\"baseRotation\":-45")
        assertFalse(result.diagnostics.any { it.code == "JAVA_LOCAL_VALUE_OMITTED" })
    }

    @Test
    fun `promotes nested missile units and rewrites spawnUnit to a content reference`() {
        val result = exportJava(
            "src/NestedMissile.java" to
                """
                class NestedMissile {
                    static Block launcher = new PowerTurret("launcher") {{
                        shootType = new BulletType() {{
                            spawnUnit = new MissileUnitType("fixture-missile") {{
                                speed = 7f;
                                lifetime = 90f;
                                collidesAir = true;
                                abilities.add(new ForceFieldAbility(20f, 1f, 100f, 60f));
                                weapons.add(new Weapon("payload") {{
                                    mirror = false;
                                    bullet = new BulletType(0f, 50f) {{
                                        splashDamage = 80f;
                                    }};
                                }});
                            }};
                        }};
                    }};
                }
                """.trimIndent(),
        )

        val launcher = result.generatedJson("content/blocks/launcher.hjson")
        assertContainsJson(launcher, "\"spawnUnit\":\"dp-fixture-missile\"")

        val missile = result.generatedJson("content/units/fixture-missile.hjson")
        assertContainsJson(missile, "\"template\":\"MissileUnitType\"")
        assertContainsJson(missile, "\"type\":\"missile\"")
        assertContainsJson(missile, "\"speed\":7,\"lifetime\":90")
        assertFalse(missile.contains("\"collidesAir\""), missile)
        assertContainsJson(missile, "\"abilities\":[{\"type\":\"ForceFieldAbility\"")
        assertContainsJson(missile, "\"weapons\":[{\"name\":\"payload\",\"mirror\":false")
        assertContainsJson(missile, "\"bullet\":{\"type\":\"BulletType\",\"speed\":0,\"damage\":50")
        assertTrue(result.diagnostics.any { it.code == "JAVA_NESTED_UNIT_PROMOTED" })
        assertTrue(result.diagnostics.any {
            it.code == "JAVA_TARGET_FIELD_OMITTED" && it.message.contains("collidesAir")
        })
        assertFalse(result.diagnostics.any { it.code == "JAVA_NESTED_UNIT_REQUIRES_PROMOTION" })
        assertFalse(result.diagnostics.any {
            it.code == "JAVA_FIELD_EXPRESSION_OMITTED" && it.message.contains("spawnUnit")
        })
    }

    @Test
    fun `applies deterministic cross content assignments and approximates load time random values`() {
        val result = exportJava(
            "src/CrossContent.java" to
                """
                class CrossContent {
                    static Block floor = new Floor("fixture-floor") {{ }};
                    static Block prop = new TallBlock("fixture-prop") {{
                        ((Floor)floor).decoration = this;
                    }};
                    static Block turret = new PowerTurret("fixture-turret") {{
                        shootType = new BasicBulletType(random(10f) + 8f, 20f) {{
                            fragBullet = ((LiquidTurret)Blocks.tsunami).ammoTypes.get(Liquids.slag);
                        }};
                    }};
                }
                """.trimIndent(),
        )

        val floor = result.generatedJson("content/blocks/fixture-floor.hjson")
        assertContainsJson(floor, "\"decoration\":\"dp-fixture-prop\"")
        val turret = result.generatedJson("content/blocks/fixture-turret.hjson")
        assertContainsJson(turret, "\"speed\":13,\"damage\":20")
        assertContainsJson(turret, "\"fragBullet\":{\"type\":\"LiquidBulletType\",\"liquid\":\"slag\"")
        assertContainsJson(turret, "\"status\":\"melting\",\"hitColor\":\"ffa166ff\"")
        assertTrue(result.diagnostics.any { it.code == "JAVA_CROSS_CONTENT_ASSIGNMENT_APPLIED" })
        assertTrue(result.diagnostics.any { it.code == "JAVA_RANDOM_EXPRESSION_APPROXIMATED" })
        assertTrue(result.diagnostics.any { it.code == "JAVA_VANILLA_OBJECT_SNAPSHOT_APPLIED" })
        assertFalse(result.diagnostics.any { it.code == "JAVA_FIELD_TARGET_UNRESOLVED" })
        assertFalse(result.diagnostics.any { it.code == "JAVA_FIELD_EXPRESSION_OMITTED" })
    }

    @Test
    fun `expands bounded classic for loops in root and nested data builders`() {
        val result = exportJava(
            "src/StaticLoops.java" to
                """
                class StaticLoops {
                    static UnitType loopUnit = new UnitType("loop-unit") {{
                        for (int i = 0; i < 2; i++) {
                            int fi = i;
                            abilities.add(new ShieldArcAbility() {{
                                x = -8 * (fi > 0 ? 1 : -1);
                                angleOffset = fi > 0 ? 80f : -80f;
                            }});
                        }
                    }};

                    static Block loopBlock = new PowerTurret("loop-block") {{
                        drawer = new DrawTurret() {{
                            for (int i = 0; i < 3; i++) {
                                float f = i;
                                parts.add(new ShapePart() {{
                                    stroke = 2.5f + f * 1.5f;
                                    radiusTo = 25f + 20f * f;
                                }});
                            }
                        }};
                    }};
                }
                """.trimIndent(),
        )

        val unit = result.generatedJson("content/units/loop-unit.hjson")
        assertContainsJson(
            unit,
            "\"abilities\":[{\"type\":\"ShieldArcAbility\",\"x\":8,\"angleOffset\":-80},{\"type\":\"ShieldArcAbility\",\"x\":-8,\"angleOffset\":80}]",
        )

        val block = result.generatedJson("content/blocks/loop-block.hjson")
        assertContainsJson(block, "\"parts\":[{\"type\":\"ShapePart\",\"stroke\":2.5,\"radiusTo\":25}")
        assertContainsJson(block, "{\"type\":\"ShapePart\",\"stroke\":4,\"radiusTo\":45}")
        assertContainsJson(block, "{\"type\":\"ShapePart\",\"stroke\":5.5,\"radiusTo\":65}]")
        assertEquals(2, result.diagnostics.count { it.code == "JAVA_FOR_LOOP_EXPANDED" })
        assertFalse(result.diagnostics.any { it.code == "JAVA_FOR_LOOP_UNSUPPORTED" })
    }

    @Test
    fun `stops combinatorial classic for expansion at the per declaration budget`() {
        val result = exportJava(
            "src/LoopBudget.java" to
                """
                class LoopBudget {
                    static UnitType guarded = new UnitType("guarded") {{
                        for (int i = 0; i < 64; i++) {
                            weapons.add(new Weapon() {{
                                for (int j = 0; j < 64; j++) {
                                    for (int k = 0; k < 64; k++) {
                                    }
                                }
                            }});
                        }
                    }};
                }
                """.trimIndent(),
        )

        // Returning a generated file proves the untrusted combinatorial input was bounded rather
        // than exhausting memory/CPU or aborting the complete export.
        result.generatedJson("content/units/guarded.hjson")
        assertEquals(
            ContentDisposition.DEGRADED,
            result.contentResults.single { it.outputPath == "content/units/guarded.hjson" }.disposition,
        )
        assertTrue(result.diagnostics.any { diagnostic ->
            diagnostic.code == "JAVA_FOR_LOOP_BUDGET_EXCEEDED" && diagnostic.message.contains("4096")
        })
    }

    @Test
    fun `rejects classic for nesting deeper than the per declaration limit`() {
        val result = exportJava(
            "src/LoopDepth.java" to
                """
                class LoopDepth {
                    static UnitType guarded = new UnitType("guarded") {{
                        for (int i0 = 0; i0 < 1; i0++) {
                            for (int i1 = 0; i1 < 1; i1++) {
                                for (int i2 = 0; i2 < 1; i2++) {
                                    for (int i3 = 0; i3 < 1; i3++) {
                                        for (int i4 = 0; i4 < 1; i4++) {
                                            for (int i5 = 0; i5 < 1; i5++) {
                                                for (int i6 = 0; i6 < 1; i6++) {
                                                    for (int i7 = 0; i7 < 1; i7++) {
                                                        for (int i8 = 0; i8 < 1; i8++) {
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }};
                }
                """.trimIndent(),
        )

        result.generatedJson("content/units/guarded.hjson")
        assertEquals(
            ContentDisposition.DEGRADED,
            result.contentResults.single { it.outputPath == "content/units/guarded.hjson" }.disposition,
        )
        assertTrue(result.diagnostics.any { diagnostic ->
            diagnostic.code == "JAVA_FOR_LOOP_DEPTH_EXCEEDED" && diagnostic.message.contains("8 levels")
        })
    }

    private fun exportJava(vararg sources: Pair<String, String>): StaticExportResult =
        JavaAstStaticExporter().export(
            StaticExportContext(
                detectedKind = DetectedSourceKind.MOD,
                sourceName = "fixture-mod",
                slug = "fixture-mod",
                modNamespace = "fixture-mod",
                files = sources.map { (path, source) -> StaticSourceFile(path, source.toByteArray()) },
            ),
        )

    private fun StaticExportResult.generatedJson(path: String): String {
        val file = generatedFiles.singleOrNull { it.outputPath == path }
        assertNotNull(file, "Missing generated file $path; emitted: ${generatedFiles.map { it.outputPath }}")
        return file.bytes.toString(Charsets.UTF_8).replace(Regex("\\s+"), "")
    }

    private fun assertContainsJson(actual: String, expectedFragment: String) {
        assertTrue(actual.contains(expectedFragment), "Expected JSON fragment $expectedFragment in:\n$actual")
    }
}
