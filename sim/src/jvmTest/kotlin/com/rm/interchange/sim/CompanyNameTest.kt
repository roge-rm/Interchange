package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CompanyNameTest {
    private fun game(seed: Long = 7, rivals: Int = 3) =
        World(NewGame(128, 128, seed, 1930, Region.MIXED, towns = 3, industries = 0, rivals = rivals))

    @Test
    fun theFirstCompanyIsNamedAfterTheBiggestTown() {
        val w = game()
        val biggest = w.towns.sortedWith(compareByDescending<Town> { it.population }.thenBy { it.id }).first()
        assertTrue(biggest.name in w.companies[0].name, w.companies[0].name)
    }

    @Test
    fun everyCompanyHasItsOwnName() {
        // Three towns and up to seven rivals, so towns are shared and names have to differ by kind or number.
        for (seed in 1L..6L) {
            val w = game(seed, rivals = 7)
            for (id in w.companies.size until MAX_COMPANIES) w.apply(Command.NewCompany(id, ""))
            val names = w.companies.map { it.name.lowercase() }
            assertEquals(names.size, names.toSet().size, "seed $seed: $names")
            assertTrue(names.none { it.isBlank() || it == "company" })
        }
    }

    @Test
    fun companiesAreNamedAfterDifferentTownsWhileThereAreEnough() {
        for (seed in 1L..6L) {
            val w = game(seed, rivals = 3)
            assertTrue(w.towns.size >= w.companies.size)
            val towns = w.companies.map { c -> w.towns.filter { it.name in c.name }.maxBy { it.name.length }.id }
            assertEquals(towns.size, towns.toSet().size, "seed $seed: ${w.companies.map { it.name }}")
        }
    }

    @Test
    fun aNewPlayerCompanyIsNamedAfterAnotherTown() {
        val w = game(rivals = 0)
        w.apply(Command.NewCompany(1, ""))
        val town = w.towns.first { it.name in w.companies[0].name }
        assertTrue(town.name !in w.companies[1].name, w.companies.map { it.name }.toString())
    }

    @Test
    fun renamingKeepsToTheRules() {
        val w = game()
        val rival = w.companies[1].name
        w.apply(Command.RenameCompany(0, "  Dan's Lines  "))
        assertEquals("Dan's Lines", w.companies[0].name)
        w.apply(Command.RenameCompany(0, "   "))
        assertEquals("Dan's Lines", w.companies[0].name)
        w.apply(Command.RenameCompany(0, rival.uppercase()))
        assertEquals("Dan's Lines", w.companies[0].name)
        w.apply(Command.RenameCompany(0, "x".repeat(100)))
        assertEquals("x".repeat(COMPANY_NAME_MOST), w.companies[0].name)
        w.apply(Command.RenameCompany(0, "A\nB"))
        assertEquals("AB", w.companies[0].name)
        assertEquals(rival, w.companies[1].name)
    }

    @Test
    fun aRenameIsInTheChecksumAndSaved() {
        val w = game()
        val before = w.checksum()
        w.apply(Command.RenameCompany(0, "Dan's Lines"))
        assertNotEquals(before, w.checksum())
        val copy = Save.read(Save.write(w))
        assertEquals("Dan's Lines", copy.companies[0].name)
        assertEquals(w.checksum(), copy.checksum())
        val c = Command.RenameCompany(0, "Dan's Lines")
        assertEquals(c, Codec.decode(Codec.encode(c)))
    }

    @Test
    fun aNameIsCutWholeCharactersAtATime() {
        val w = game()
        val name = "a".repeat(COMPANY_NAME_MOST - 1) + "\uD83D\uDE82\uD83D\uDE83"
        w.apply(Command.RenameCompany(0, name))
        assertEquals("a".repeat(COMPANY_NAME_MOST - 1) + "\uD83D\uDE82", w.companies[0].name)
        val copy = Save.read(Save.write(w))
        assertEquals(w.companies[0].name, copy.companies[0].name)
        assertEquals(w.checksum(), copy.checksum())
        // A lone half is left out.
        assertEquals("ab", cleanCompanyName("a\uD83Db"))
    }

    @Test
    fun aTownIsUsedOnlyByWholeWord() {
        val w = game(rivals = 0)
        val t = w.towns[0]
        w.companies[0].name = "${t.name}ville Transport"
        assertEquals(t, w.nameTown(t, 1))
        w.companies[0].name = "${t.name} Transport"
        assertNotEquals(t, w.nameTown(t, 1))
    }

    @Test
    fun anOldNameStaysOnLoad() {
        val w = game()
        w.companies[0].name = "Company"
        assertEquals("Company", Save.read(Save.write(w)).companies[0].name)
    }
}
