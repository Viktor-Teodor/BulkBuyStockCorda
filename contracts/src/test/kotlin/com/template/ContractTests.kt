package com.template

import com.r3.corda.lib.tokens.contracts.EvolvableTokenContract
import com.r3.corda.lib.tokens.contracts.commands.Create
import com.r3.corda.lib.tokens.contracts.commands.Update
import com.template.contracts.StockShareTokenContract
import com.template.states.StockShareToken
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.junit.Test

class ContractTests {
    private val partyA = TestIdentity(CordaX500Name("partyA", "London", "GB"))
    private val partyB = TestIdentity(CordaX500Name("partyB", "London", "GB"))
    private val partyC = TestIdentity(CordaX500Name("partyC", "London", "GB"))
    private val stocksManager = TestIdentity(CordaX500Name("StocksManager", "London", "GB"))

    private val ledgerServices = MockServices(listOf("com.template.contracts"), partyA, partyB, partyC, stocksManager)

    @Test
    fun CreateSimpleValidStockTokens() {

        ledgerServices.ledger {

            transaction {

                output(StockShareTokenContract.ID, StockShareToken(company = "Microsoft Corporations",
                                                                   companyCode = "MSFT",
                                                                    maintainer = stocksManager.party,
                                                                    price = 200.13,
                                                                    linearId = UniqueIdentifier()))
                command(partyA.publicKey, Create())
                verifies()
            }

            transaction {
                output(StockShareTokenContract.ID, StockShareToken(company = "Microsoft Corporations",
                                                                                companyCode = "MSFT",
                                                                                maintainer = stocksManager.party,
                                                                                price = 124.12,
                                                                                linearId = UniqueIdentifier()))
                command(partyB.publicKey, Create())
                verifies()
            }

            transaction {
                output(StockShareTokenContract.ID, StockShareToken(company = "BlackRock",
                                                                    companyCode = "BLK",
                                                                    maintainer = stocksManager.party,
                                                                    price = 333.33,
                                                                    linearId = UniqueIdentifier()))
                command(partyC.publicKey, Create())
                verifies()
            }
        }
    }

    @Test
    fun CreateStockTokensWithNegativePrice(){

        val expectedError = "All prices should be positive"

        ledgerServices.ledger {
            transaction {
                output(StockShareTokenContract.ID, StockShareToken(company = "Microsoft Corporations",
                        companyCode = "MSFT",
                        maintainer = stocksManager.party,
                        price = -200.13,
                        linearId = UniqueIdentifier()))

                command(partyA.publicKey, Create())
                failsWith(expectedError)
            }
        }
    }

    @Test
    fun CreateStockTokensWithEmptyCompanyNames(){
        val expectedError = ""

        ledgerServices.ledger {
            transaction {
                output(StockShareTokenContract.ID, StockShareToken(company = "",
                        companyCode = "MSFT",
                        maintainer = stocksManager.party,
                        price = 200.13,
                        linearId = UniqueIdentifier()))

                command(partyA.publicKey, Create())
                failsWith(expectedError)
            }

            transaction {
                output(StockShareTokenContract.ID, StockShareToken(company = "asdg",
                        companyCode = "",
                        maintainer = stocksManager.party,
                        price = 200.13,
                        linearId = UniqueIdentifier()))

                command(partyA.publicKey, Create())
                failsWith(expectedError)
            }
        }

    }

    @Test
    fun `linear ID can't change`() {
        val expectedMessage = "The Linear ID of the evolvable token cannot change during an update."

        ledgerServices.ledger {
            transaction {

                val inputToken = StockShareToken(company = "Microsoft Corporations",
                                                companyCode = "MSFT",
                                                maintainer = stocksManager.party,
                                                price = -200.13,
                                                linearId = UniqueIdentifier())

                val outputToken = StockShareToken(company = "Microsoft Corporations",
                                                    companyCode = "MSFT",
                                                    maintainer = stocksManager.party,
                                                    price = -200.13,
                                                    linearId = UniqueIdentifier())


                input(StockShareTokenContract.ID, inputToken)
                output(StockShareTokenContract.ID, outputToken)
                command(partyA.publicKey, Update())
                failsWith(expectedMessage)
            }
        }
    }

    @Test
    fun `dummy test`() {

    }
}