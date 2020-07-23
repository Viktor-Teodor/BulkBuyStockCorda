package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.ci.workflows.SyncKeyMappingFlow
import com.r3.corda.lib.ci.workflows.SyncKeyMappingFlowHandler
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.money.GBP
import com.r3.corda.lib.tokens.selection.database.selector.DatabaseTokenSelection
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveFungibleTokens
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveTokens
import com.r3.corda.lib.tokens.workflows.internal.flows.distribution.UpdateDistributionListFlow
import com.r3.corda.lib.tokens.workflows.internal.flows.finality.ObserverAwareFinalityFlow
import com.r3.corda.lib.tokens.workflows.internal.flows.finality.ObserverAwareFinalityFlowHandler
import com.r3.corda.lib.tokens.workflows.types.PartyAndAmount
import com.r3.corda.lib.tokens.workflows.utilities.ourSigningKeys
import com.template.states.StockShareToken
import net.corda.core.contracts.Amount
import net.corda.core.contracts.FungibleState
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.declaredField
import net.corda.core.node.services.queryBy
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap


@StartableByRPC
@InitiatingFlow
class SellStocksFlow(private val companyCode: String,
                     vararg val newHoldersNamesAndPercentages : Pair<String,Double>) : FlowLogic<SignedTransaction>() {

    @CordaSerializable
    data class PriceNotification(val amount: Amount<TokenType>)

    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        //Extract the stock from the vault
        //Need to manually make sure that there is only one amount of stocks belonging to a company
        val stockStateToBeSold = serviceHub.vaultService.queryBy<FungibleState<StockShareToken>>()
                .states
                .filter { it.ref.declaredField<String>("companyCode").toString() == companyCode }
                .map {it}


        require(newHoldersNamesAndPercentages.sumByDouble { it.second } == 100.0) {"The percentages should add up to 100"}

        //Transform the initial mapping into a list of PartyAndAmount that can be used directly in the transaction
        //it.first is the party (as a string initially then as a Party object) second is the percentage of the shares as double

        val totalNumberOfSharesToBeSold = stockStateToBeSold.single().state.data.amount.quantity
        val pointerToStockToken = stockStateToBeSold.single().state.data.amount.token.toPointer<StockShareToken>()

        val newHoldersPartiesAndPercentages : List<PartyAndAmount<TokenType>> = newHoldersNamesAndPercentages
                .map { PartyAndAmount(serviceHub.identityService.partiesFromName("organization=${it.first},locality=London, country=GB", false).single(),
                                (it.second/100.0 * totalNumberOfSharesToBeSold) of pointerToStockToken)
                }

        var totalSumOutputStates  = 0.0
        val totalCostOfStocks = stockStateToBeSold.single().state.data.amount.quantity *
                stockStateToBeSold.single().state.data.amount.token.price

        //Choose the first notary and start building the transaction
        val txBuilder = TransactionBuilder(notary = notary)
        addMoveFungibleTokens(txBuilder, serviceHub,newHoldersPartiesAndPercentages,ourIdentity)

        for (buyerParty in newHoldersPartiesAndPercentages){
            val session = initiateFlow(buyerParty.party as Party)

            // Ask for input stateAndRefs - send notification with the amount to exchange.
            session.send(PriceNotification(buyerParty.amount.quantity * totalCostOfStocks of GBP))

            // Receive GBP states back.
            val inputs = subFlow(ReceiveStateAndRefFlow<FungibleToken>(session))

            val outputs = session.receive<List<FungibleToken>>().unwrap { it }

            totalSumOutputStates += outputs.asSequence().sumByDouble { it.amount.quantity as Double }

            addMoveTokens(txBuilder, inputs, outputs)

            subFlow(SyncKeyMappingFlow(session, txBuilder.toWireTransaction(serviceHub)))

        }

        require (totalSumOutputStates == totalCostOfStocks) { "The total sum of money must be equal to the total cost of shares"}

        // Because states on the transaction can have confidential identities on them, we need to sign them with corresponding keys.
        val ourSigningKeys = txBuilder.toLedgerTransaction(serviceHub).ourSigningKeys(serviceHub)
        val initialStx = serviceHub.signInitialTransaction(txBuilder, signingPubKeys = ourSigningKeys)

        var stx : SignedTransaction = initialStx

        for (buyerParty in newHoldersPartiesAndPercentages) {
            val session = initiateFlow(buyerParty.party as Party)

            // Collect signatures from the new house owner.
            stx = subFlow(CollectSignaturesFlow(initialStx, listOf(session), ourSigningKeys))

            // Update distribution list.
            subFlow(UpdateDistributionListFlow(stx))
            // Finalise transaction! If you want to have observers notified, you can pass optional observers sessions.
            subFlow(ObserverAwareFinalityFlow(stx, listOf(session)))
        }

        return stx
    }


    @InitiatedBy(SellStocksFlow::class)
    class SellStocksFlowHandler(val otherSession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {

            // Receive notification with house price.
            val priceNotification = otherSession.receive<PriceNotification>().unwrap { it }

            // Generate fresh key, possible change outputs will belong to this key.
            val changeHolder = serviceHub.keyManagementService.freshKeyAndCert(ourIdentityAndCert, false).party.anonymise()

            // Chose state and refs to send back.
            val (inputs, outputs) = DatabaseTokenSelection(serviceHub).generateMove(
                    lockId = runId.uuid,
                    partiesAndAmounts = listOf(Pair(otherSession.counterparty, priceNotification.amount)),
                    changeHolder = changeHolder
            )

            subFlow(SendStateAndRefFlow(otherSession, inputs))

            otherSession.send(outputs)

            subFlow(SyncKeyMappingFlowHandler(otherSession))

            subFlow(object : SignTransactionFlow(otherSession) {
                override fun checkTransaction(stx: SignedTransaction) {
                    // We should perform some basic sanity checks before signing the transaction. This step was omitted for simplicity.
                }
            })
            subFlow(ObserverAwareFinalityFlowHandler(otherSession))
        }
    }
}
