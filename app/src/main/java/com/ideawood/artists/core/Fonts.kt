package com.ideawood.artists.core

/** The curated open-licence fonts bundled with the app (same list and order as the desktop app). */
data class FontChoice(val family: String, val label: String, val mood: String, val bold: Boolean = false)

val CURATED_FONTS = listOf(
    FontChoice("Poppins", "Poppins Bold", "Clean modern pop / afrobeats / R&B", true),
    FontChoice("Poppins ExtraBold", "Poppins ExtraBold", "Heavy karaoke look, very readable"),
    FontChoice("Bebas Neue", "Bebas Neue", "Tall poster caps - hip-hop, drill, dancehall"),
    FontChoice("Anton", "Anton", "Loud condensed headline - trap, street"),
    FontChoice("Staatliches", "Staatliches", "Retro poster caps - reggae, funk"),
    FontChoice("Archivo Black", "Archivo Black", "Solid grotesque - rap, rock"),
    FontChoice("Alfa Slab One", "Alfa Slab One", "Chunky slab - gospel, country, vintage"),
    FontChoice("Abril Fatface", "Abril Fatface", "High-contrast fashion serif - soul, ballads"),
    FontChoice("DM Serif Display", "DM Serif Display", "Elegant serif - acoustic, cinematic"),
    FontChoice("Great Vibes", "Great Vibes", "Formal calligraphy - wedding, worship, love songs"),
    FontChoice("Lobster", "Lobster", "Friendly retro script - pop, feel-good"),
    FontChoice("Pacifico", "Pacifico", "Surf-style script - summer, tropical"),
    FontChoice("Righteous", "Righteous", "Futuristic rounded - EDM, synth"),
    FontChoice("Bungee", "Bungee", "Signage block - dance, hype"),
    FontChoice("Monoton", "Monoton", "Neon multi-line - nightlife, disco"),
)
