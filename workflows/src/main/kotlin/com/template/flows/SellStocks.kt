package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.states.EvolvableTokenType
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.money.USD
import com.r3.corda.lib.tokens.selection.TokenQueryBy
import com.r3.corda.lib.tokens.selection.database.selector.DatabaseTokenSelection
import com.r3.corda.lib.tokens.selection.tokenAmountCriteria
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveFungibleTokens
import com.r3.corda.lib.tokens.workflows.types.PartyAndAmount
import com.r3.corda.lib.tokens.workflows.utilities.tokenAmountCriteria
import com.template.states.StockShareToken
import jdk.nashorn.internal.parser.TokenType
import net.corda.core.contracts.Amount
import net.corda.core.contracts.FungibleState
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.declaredField
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.CriteriaExpression
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.nio.DoubleBuffer


@StartableByRPC
@InitiatingFlow
class SellStocksFlow(vararg val newHoldersNamesAndPercentages : Pair<String,Double>,
                     private val companyCode: String) : FlowLogic<SignedTransaction>() {

    @CordaSerializable
    data class PriceNotification(val amount: Amount<TokenType>)

    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        val issuerName: CordaX500Name = CordaX500Name(
                organisation = "StocksManager",
                locality = "London",
                country = "GB")

        val issuerParty: Party = serviceHub.identityService.wellKnownPartyFromX500Name(issuerName) ?:
        throw IllegalArgumentException("Couldn't find counter party for StocksManager in identity service")

        val sequenceOfNewHolders = newHoldersNamesAndPercentages.asSequence()

        require(sequenceOfNewHolders.sumByDouble { it.second } == 100.0) {"The percentages should add up to 100"}

        val newHoldersPartiesAndPercentages : List<Pair<Party,Double>> = sequenceOfNewHolders.map{
           Pair(serviceHub.identityService.wellKnownPartyFromX500Name(CordaX500Name(organisation = "${it.first}",
                                                                                    locality = "London",
                                                                                    country = "GB")) ?:
           throw IllegalArgumentException("Couldn't find counter party for $it.first in identity service"), it.second)
       }.toList()

        //Need to manually make sure that there is only one amount of stocks belonging to a company
        val stockStateToBeSold = serviceHub.vaultService.queryBy<FungibleState<StockShareToken>>()
                .states
                .asSequence()
                .filter { it.ref.declaredField<String>("companyCode").toString() == companyCode }
                .map {it}
                .toList()

        val test = PartyAndAmount(ourIdentity, stockStateToBeSold.single().state.data.amount)
        //Choose the first notary and start building the transaction
        val txBuilder = TransactionBuilder(notary = notary)
        addMoveFungibleTokens(txBuilder, serviceHub,)

    }
}