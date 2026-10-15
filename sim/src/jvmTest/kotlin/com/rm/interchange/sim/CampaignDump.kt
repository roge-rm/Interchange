package com.rm.interchange.sim

import java.io.File
import kotlin.test.Test

/**
 * Draws each ready campaign chapter's map as text, to look over its land while shaping it: ~ water, digits the
 * height, r rock, # town, I industry; maps over 128 a side at every other tile. Then its towns, industries, goals
 * and companies. Only runs with CAMPAIGN_DUMP set to a folder; each goes there as ID.txt.
 */
class CampaignDump {
    @Test
    fun dump() {
        val out = System.getenv("CAMPAIGN_DUMP") ?: return
        for (id in Campaign.CHAPTERS.filter { it.ready }.map { it.id }) {
            val w = Campaign.build(id, "t", "i")!!
            val sb = StringBuilder()
            val step = if (w.width > 128) 2 else 1
            for (y in 0 until w.height step step) {
                for (x in 0 until w.width step step) {
                    val i = w.index(x, y)
                    sb.append(when {
                        w.townOf[i].toInt() >= 0 || w.road[i].toInt() != 0 -> '#'
                        w.industryOf[i].toInt() != 0 -> 'I'
                        w.isWater(x, y) -> '~'
                        w.ground[i].toInt() == Ground.ROCK -> 'r'
                        else -> "0123456789abcdefghijklmnop"[w.heightAt(x, y)]
                    })
                }
                sb.append('\n')
            }
            sb.append(w.towns.joinToString { "${it.name} ${it.x},${it.y} pop ${it.population}" }).append('\n')
            sb.append(w.industries.joinToString { "${w.roster.industries[it.kind].key} ${it.x},${it.y}" }).append('\n')
            sb.append(w.goals.joinToString { "k${it.kind} ${it.amount} t${it.town} t${it.town2} i${it.industry}" }).append('\n')
            sb.append(w.companies.joinToString { it.name }).append('\n')
            File(out, "$id.txt").writeText(sb.toString())
        }
    }
}
