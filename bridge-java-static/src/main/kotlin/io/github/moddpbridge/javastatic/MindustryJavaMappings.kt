package io.github.moddpbridge.javastatic

import io.github.moddpbridge.model.ContentKind

internal object MindustryJavaMappings {
    val customBlockFallbacks: Map<String, String> = mapOf(
        "EnhancedPowerTurret" to "PowerTurret",
        "ExplodeWall" to "Wall",
        "GasTurbineGenerator" to "ConsumeGenerator",
        "PressureDrill" to "Drill",
        "SFCore" to "CoreBlock",
    )

    val customBulletFallbacks: Map<String, String> = mapOf(
        "PowerupBullet" to "BasicBulletType",
        "ShieldBreakBullet" to "BasicBulletType",
        "SizeDamageBullet" to "BasicBulletType",
    )

    val unsupportedCustomFields: Map<String, Set<String>> = mapOf(
        "PowerupBullet" to setOf("maxDamageMultiplier", "damageUp"),
        "ShieldBreakBullet" to setOf("shieldDamagePercent", "shbreakEffect"),
        "SizeDamageBullet" to setOf("sizeDamageMul", "basicSize"),
        "EnhancedPowerTurret" to setOf("enhancerItem", "enhancedBullet", "enhancedPattern", "maxEnhanced"),
        "ExplodeWall" to setOf("checkInterval", "range", "expDamage", "shake", "expLimit", "buildingDamageMultiplier", "waveEffect", "waveColor"),
        // ConsumeGenerator already exposes warmupSpeed in v159.7, so it survives the fallback.
        "GasTurbineGenerator" to setOf("extraPower", "powerUpSpeed"),
        "PressureDrill" to setOf("maxFactor", "minPowerNeed"),
        "SFCore" to setOf("maxNumber"),
    )

    /**
     * Fields present in some older/mod-side Java APIs but rejected by the v159.7 DataPatcher
     * class map. Keeping these fields would turn a best-effort conversion into a parser warning
     * (or, for stricter fields, a failed content declaration), so omit them deterministically and
     * surface a degradation diagnostic instead.
     */
    val unsupportedDataFields: Map<String, Set<String>> = mapOf(
        "DrawLiquidTile" to setOf("drawLiquidLight"),
        "LightningBulletType" to setOf("frontColor"),
        "ParticleEffect" to setOf("color"),
        "RegionPart" to setOf("top"),
        "WrapEffect" to setOf("lightOpacity"),
        "ArmorPlateAbility" to setOf("healFlash"),
        "PointBulletType" to setOf("bullet"),
        "BasicBulletType" to setOf("ejectEffect"),
        "ShapePart" to setOf("lightOpacity"),
        // Bare collidesAir assignments inside a nested MissileUnitType initializer resolve to
        // the lexically enclosing BulletType in Java; MissileUnitType itself has no such field.
        // Keeping it on the promoted unit produces a DataPatcher unknown-field warning.
        "MissileUnitType" to setOf("collidesAir"),
    )

    fun unsupportedFields(sourceType: String): Set<String> =
        unsupportedCustomFields[sourceType].orEmpty() + unsupportedDataFields[sourceType].orEmpty()

    /** Target v159 constants needed by the first static export slice. */
    val colorConstants: Map<String, String> = mapOf(
        "Color.white" to "ffffffff",
        "Color.black" to "000000ff",
        "Color.gray" to "7f7f7fff",
        "Color.lightGray" to "bfbfbfff",
        "Color.darkGray" to "3f3f3fff",
        "Color.red" to "ff0000ff",
        "Color.green" to "00ff00ff",
        "Color.blue" to "0000ffff",
        "Color.yellow" to "ffff00ff",
        "Color.orange" to "ffa500ff",
        "Color.sky" to "87ceebff",
        "Color.clear" to "00000000",
        "Pal.heal" to "98ffa9ff",
        "Pal.gray" to "454545ff",
        "Pal.accent" to "ffd37fff",
        "Pal.lancerLaser" to "a9d8ffff",
        "Pal.bulletYellow" to "fff8e8ff",
        "Pal.bulletYellowBack" to "f9c27aff",
        "Pal.blastAmmoBack" to "e9665bff",
        "Pal.blastAmmoFront" to "eeab89ff",
        "Pal.lightOrange" to "f68021ff",
        "Pal.lightishOrange" to "f8ad42ff",
        "Pal.lighterOrange" to "f6e096ff",
        "Pal.lightishGray" to "a2a2a2ff",
        "Pal.techBlue" to "8ca9e8ff",
        "Pal.techBlueShot" to "ffffffaa",
        "Pal.sap" to "665c9fff",
        "Pal.sapBullet" to "bf92f9ff",
        "Pal.sapBulletBack" to "6d56bfff",
        "Pal.surge" to "f3e979ff",
        "Pal.plastanium" to "a1b46eff",
        "Pal.plastaniumBack" to "d8d97fff",
        "Pal.plastaniumFront" to "fffac6ff",
        "Pal.meltdownHit" to "ffb98bff",
        "Pal.remove" to "e55454ff",
        "Pal.noplace" to "ffa697ff",
        "Pal.power" to "fbad67ff",
        "Pal.powerLight" to "fbd367ff",
        "Pal.lightPyraFlame" to "ffb855ff",
        "Pal.darkPyraFlame" to "db661cff",
        "Pal.orangeSpark" to "d2b29cff",
        "Pal.redSpark" to "fbb97fff",
        "Pal.thoriumPink" to "f9a3c7ff",
        "Pal.thoriumAmmoBack" to "f595beff",
        "Pal.thoriumAmmoFront" to "ffffffff",
        "Pal.graphiteAmmoFront" to "dae1eeff",
        "Pal.graphiteAmmoBack" to "7d89d8ff",
        "Pal.surgeAmmoFront" to "ffffffff",
        "Pal.surgeAmmoBack" to "f3e979ff",
        "Pal.turretHeat" to "ab3400ff",
        "Items.copper.color" to "d99d73ff",
        "Items.lead.color" to "8c7fa9ff",
        "Items.metaglass.color" to "ebeef5ff",
        "Items.graphite.color" to "b2c6d2ff",
        "Items.sand.color" to "f7cba4ff",
        "Items.coal.color" to "272727ff",
        "Items.titanium.color" to "8da1e3ff",
        "Items.thorium.color" to "f9a3c7ff",
        "Items.scrap.color" to "777777ff",
        "Items.silicon.color" to "53565cff",
        "Items.plastanium.color" to "cbd97fff",
        "Items.phaseFabric.color" to "f4ba6eff",
        "Items.surgeAlloy.color" to "f3e979ff",
        "Items.sporePod.color" to "7457ceff",
        "Items.blastCompound.color" to "ff795eff",
        "Items.pyratite.color" to "ffaa5fff",
        "Items.beryllium.color" to "3a8f64ff",
        "Items.tungsten.color" to "768a9aff",
        "Items.oxide.color" to "e4ffd6ff",
        "Items.carbide.color" to "89769aff",
        "Liquids.cryofluid.color" to "6ecdecff",
    )

    /** Numeric constants that would otherwise be emitted as invalid strings for float/int fields. */
    val numericConstants: Map<String, String> = mapOf(
        "Float.MAX_VALUE" to "340282346638528859811704183484516925440",
        "Float.MIN_VALUE" to "0.0000000000000000000000000000000000000000000014",
        "Mathf.PI" to "3.14159265358979323846",
        "Mathf.PI2" to "6.28318530717958647692",
        "Mathf.degRad" to "0.01745329251994329577",
        "Mathf.radDeg" to "57.2957795130823208768",
        "Layer.groundUnit" to "60",
        "Layer.bullet" to "100",
        "Fx.lightning.lifetime" to "10",
    )

    val staticImportedContent: Map<String, String> = buildMap {
        // StatusEffects.* is frequently statically imported by Java mods.
        listOf(
            "none", "burning", "freezing", "wet", "muddy", "melting", "sapped",
            "electrified", "sporeSlowed", "tarred", "overdrive", "overclock",
            "shielded", "boss", "shocked", "blasted", "corroded", "disarmed",
            "invincible", "unmoving", "slow", "fast", "overclock",
        ).forEach { put(it, camelToKebab(it)) }
        // Liquids.* static imports.
        listOf("water", "slag", "oil", "cryofluid", "arkycite", "ozone", "hydrogen", "nitrogen", "cyanogen", "neoplasm")
            .forEach { putIfAbsent(it, camelToKebab(it)) }
    }

    val classStaticStringPrefixes: Set<String> = setOf(
        "Fx",
        "Interp",
        "Blending",
        "CacheLayer",
        "BuildVisibility",
        "Category",
        "BlockGroup",
        "BlockFlag",
        "TargetPriority",
        "Sounds",
        "Bullets",
        "UnitSorts",
        "UnitCommand",
        "UnitStance",
        "Team",
        "PartProgress",
    )

    private val flyingConstructors = setOf(
        "flare", "horizon", "zenith", "antumbra", "eclipse", "mono", "poly", "mega",
        "quad", "oct", "alpha", "beta", "gamma",
    )
    private val legsConstructors = setOf("atrax", "spiroct", "arkyid", "toxopid", "corvus")
    private val tankConstructors = setOf("stell", "locus", "precept", "vanquish", "conquer")
    private val navalConstructors = setOf("risso", "minke", "bryde", "sei", "omura", "navanax")
    private val hoverConstructors = setOf("elude", "avert", "obviate", "quell", "disrupt")
    private val mechConstructors = setOf("dagger", "mace", "fortress", "scepter", "reign")

    fun unitEntityType(expression: String): String? {
        val normalized = expression.replace(" ", "")
        when {
            "TimedKillUnit" in normalized -> return "missile"
            "TankUnit" in normalized -> return "tank"
            "UnitWaterMove" in normalized -> return "naval"
            "ElevationMoveUnit" in normalized -> return "hover"
            "LegsUnit" in normalized -> return "legs"
            "PayloadUnit" in normalized -> return "payload"
            "BuildingTetherPayloadUnit" in normalized -> return "tether"
            "CrawlUnit" in normalized -> return "crawl"
            "MechUnit" in normalized -> return "mech"
            "UnitEntity" in normalized -> return "flying"
        }
        val vanilla = Regex("UnitTypes\\.([A-Za-z0-9_]+)\\.constructor").find(normalized)?.groupValues?.get(1)
            ?: return null
        return when (vanilla) {
            in flyingConstructors -> "flying"
            in legsConstructors -> "legs"
            in tankConstructors -> "tank"
            in navalConstructors -> "naval"
            in hoverConstructors -> "hover"
            in mechConstructors -> "mech"
            "assemblyDrone" -> "payload"
            else -> null
        }
    }

    fun consumeKey(type: String?): String? = when (type) {
        "ConsumeLiquid" -> "liquid"
        "ConsumeLiquids" -> "liquids"
        "ConsumeCoolant" -> "coolant"
        "ConsumeLiquidFlammable" -> "liquidFlammable"
        "ConsumeItems" -> "items"
        "ConsumeItemCharged" -> "itemCharged"
        "ConsumeItemFlammable" -> "itemFlammable"
        "ConsumeItemRadioactive" -> "itemRadioactive"
        "ConsumeItemExplosive" -> "itemExplosive"
        "ConsumeItemExplode" -> "itemExplode"
        "ConsumePower" -> "power"
        else -> null
    }

    /** Positional constructors used throughout vanilla content declarations. */
    fun constructorFields(type: String): List<String> = when (type) {
        "ItemStack" -> listOf("item", "amount")
        "LiquidStack" -> listOf("liquid", "amount")
        "PayloadStack" -> listOf("item", "amount")
        "UnitPlan" -> listOf("unit", "time", "requirements")
        "AssemblerUnitPlan" -> listOf("unit", "time", "requirements")
        "UnitEngine" -> listOf("x", "y", "radius", "rotation")
        "Rect" -> listOf("x", "y", "width", "height")
        "Vec2" -> listOf("x", "y")
        "Vec3" -> listOf("x", "y", "z")
        "PartMove" -> listOf("progress", "x", "y", "gx", "gy", "rot")
        "Weapon", "PointDefenseWeapon", "RepairBeamWeapon" -> listOf("name")
        "StatusFieldAbility" -> listOf("effect", "duration", "reload", "range")
        "MoveEffectAbility" -> listOf("x", "y", "color", "effect", "interval")
        "ForceFieldAbility" -> listOf("radius", "regen", "max", "cooldown", "sides", "rotation")
        "RegenAbility" -> listOf("amount")
        "ArmorPlateAbility" -> listOf("healthMultiplier")
        "ShieldRegenFieldAbility" -> listOf("amount", "max", "reload", "range")
        "RepairFieldAbility" -> listOf("amount", "reload", "range")
        "EnergyFieldAbility" -> listOf("damage", "reload", "range")
        "SuppressionFieldAbility" -> listOf("orbRadius", "particleSize", "y", "color")
        "ShootAlternate" -> listOf("spread")
        "ShootSpread" -> listOf("shots", "spread")
        "ShootHelix" -> listOf("scl", "mag")
        "ShootMulti" -> listOf("source", "dest")
        "RegionPart" -> listOf("suffix", "blending", "color")
        "DrawRegion" -> listOf("suffix", "rotateSpeed", "spinSprite")
        "DrawTurret" -> listOf("basePrefix")
        "DrawLiquidRegion" -> listOf("drawLiquid")
        "DrawLiquidTile" -> listOf("drawLiquid", "padding")
        "DrawWarmupRegion" -> listOf("suffix")
        "WrapEffect" -> listOf("effect", "color", "rotation")
        "RadialEffect" -> listOf("effect", "amount", "spacing", "rotationOffset")
        "BasicBulletType", "MissileBulletType", "ArtilleryBulletType", "FlakBulletType",
        "PointBulletType", "EmpBulletType", "PowerupBullet", "ShieldBreakBullet", "SizeDamageBullet" ->
            listOf("speed", "damage", "sprite")
        "BulletType" -> listOf("speed", "damage")
        "LaserBulletType", "LightningBulletType", "ShrapnelBulletType", "ContinuousLaserBulletType",
        "ContinuousFlameBulletType" -> listOf("damage")
        "ExplosionBulletType" -> listOf("splashDamage", "splashDamageRadius")
        "FireBulletType" -> listOf("speed", "damage")
        "LiquidBulletType" -> listOf("liquid")
        "ConsumeLiquid" -> listOf("liquid", "amount")
        "ConsumeLiquidFlammable", "ConsumeItemRadioactive", "ConsumeItemExplode" -> listOf("amount")
        else -> emptyList()
    }

    val constructorTypeOmitted: Set<String> = setOf(
        "ItemStack", "LiquidStack", "PayloadStack", "UnitPlan", "AssemblerUnitPlan", "UnitEngine",
        "Rect", "Vec2", "Vec3", "PartMove", "Weapon",
    )

    fun classifyDeclaredType(typeName: String): ContentKind? = when (typeName.substringAfterLast('.')) {
        "Item" -> ContentKind.ITEM
        "Liquid" -> ContentKind.LIQUID
        "StatusEffect" -> ContentKind.STATUS
        "UnitType", "TankUnitType", "MissileUnitType" -> ContentKind.UNIT
        "Block" -> ContentKind.BLOCK
        else -> null
    }

    fun targetRootType(candidate: JavaRootCandidate): Pair<String, Boolean> = when (candidate.kind) {
        ContentKind.ITEM -> "Item" to false
        ContentKind.LIQUID -> candidate.constructedType to false
        ContentKind.STATUS -> "StatusEffect" to false
        ContentKind.UNIT -> candidate.constructedType to false
        ContentKind.BLOCK -> customBlockFallbacks[candidate.constructedType]?.let { it to true }
            ?: (candidate.constructedType to false)
        ContentKind.WEATHER -> candidate.constructedType to false
    }

    fun vanillaContentName(owner: String, symbol: String): String = when (owner) {
        "Items", "Liquids", "StatusEffects", "Blocks", "UnitTypes" -> camelToKebab(symbol)
        else -> symbol
    }

    fun camelToKebab(value: String): String = buildString(value.length + 4) {
        value.forEachIndexed { index, character ->
            if (character.isUpperCase() && index > 0 && lastOrNull() != '-') append('-')
            append(character.lowercaseChar())
        }
    }
}
