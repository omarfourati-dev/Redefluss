package de.omarfourati.redefluss.pronunciation

import de.omarfourati.redefluss.testConfig
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import kotlin.test.*

class ScorerForTest {
    private val warnings = mutableListOf<String>()
    private var httpBuilt = 0
    private val http = { httpBuilt++; HttpClient(MockEngine { error("no network in tests") }) }

    private fun scorer(vararg env: Pair<String, String>) = scorerFor(testConfig(*env), http) { warnings += it }

    @Test fun fakeIsTheFakeScorer() {
        assertIs<FakeScorer>(scorer())
        assertTrue(warnings.isEmpty())
    }

    @Test fun azureWithKeyAndValidRegion() {
        assertIs<AzureScorer>(scorer("PRONUNCIATION" to "azure", "AZURE_SPEECH_KEY" to "secret-key-123", "AZURE_SPEECH_REGION" to "west-europe2"))
        assertEquals(1, httpBuilt)
    }

    @Test fun azureWithoutKeyIsDisabled() {
        assertNull(scorer("PRONUNCIATION" to "azure"))
        assertEquals(1, warnings.size)
        assertEquals(0, httpBuilt)
    }

    @Test fun invalidRegionIsDisabledAndTheKeyIsNotLogged() {
        for (region in listOf("evil.example.com/x", "GermanyWestCentral", "a b", "x?y=1")) {
            warnings.clear()
            assertNull(scorer("PRONUNCIATION" to "azure", "AZURE_SPEECH_KEY" to "secret-key-123", "AZURE_SPEECH_REGION" to region), region)
            assertEquals(1, warnings.size)
            assertFalse("secret-key-123" in warnings.single())
        }
        assertEquals(0, httpBuilt)
    }
}
