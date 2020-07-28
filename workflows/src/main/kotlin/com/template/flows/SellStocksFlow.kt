package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.ci.workflows.SyncKeyMappingFlow
import com.r3.corda.lib.ci.workflows.SyncKeyMappingFlowHandler
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType
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
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault.StateStatus
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.QueryCriteria.LinearStateQueryCriteria
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap
import java.util.*


@StartableByRPC
@InitiatingFlow
class SellStocksFlow(val companyCode: String,
                     val newHoldersNamesAndPercentages : List<Pair<String,Double>>) : FlowLogic<SignedTransaction>() {

    @CordaSerializable
    data class PriceNotification(val amount: Amount<TokenType>)

    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        //Extract the stock from the vault
        //Need to manually make sure that there is only one amount of stocks belonging to a company
        val allFungibleStatesInTheVault = serviceHub.vaultService.queryBy<FungibleState<StockShareToken>>()
                .states

        val idStockStateToBeSold = allFungibleStatesInTheVault
                .map {(it.state.data.amount.token as IssuedTokenType).tokenType.tokenIdentifier}

        val mapOfIDAndAmount : MutableMap<String, Amount<StockShareToken>> = mutableMapOf()

        for (fungibleState in allFungibleStatesInTheVault){
            mapOfIDAndAmount.put(
                    (fungibleState.state.data.amount.token as IssuedTokenType).tokenType.tokenIdentifier,
                    fungibleState.state.data.amount)
        }

        var uuid : UUID
        var queryCriteria : QueryCriteria
        var state : StateAndRef<StockShareToken>
        var stockStateToBeSold : StateAndRef<StockShareToken>? = null
        var amountOfStateToBeSold : Long = 0

        for (id in idStockStateToBeSold){
            val uuid = UUID.fromString(id)
            val queryCriteria = LinearStateQueryCriteria(null, Arrays.asList(uuid), null, StateStatus.UNCONSUMED)
            val state = serviceHub.vaultService.queryBy<StockShareToken>(queryCriteria).states.single()

            if (state.state.data.companyCode == companyCode){
                stockStateToBeSold = state
                amountOfStateToBeSold = mapOfIDAndAmount.get(state.state.data.linearId.toString())!!.quantity

            }
        }


        require(newHoldersNamesAndPercentages.sumByDouble { it.second } == 100.0) {"The percentages should add up to 100"}

        //Transform the initial mapping into a list of PartyAndAmount that can be used directly in the transaction
        //it.first is the party (as a string initially then as a Party object) second is the percentage of the shares as double
        amountOfStateToBeSold /= 10000
        val pointerToStockToken = stockStateToBeSold!!.state.data.toPointer<StockShareToken>()

        val newHoldersPartiesAndPercentages : List<PartyAndAmount<TokenType>> = newHoldersNamesAndPercentages
                .map { PartyAndAmount(serviceHub.identityService.partiesFromName(it.first, false).single(),
                                (it.second/100.0 * amountOfStateToBeSold) of pointerToStockToken)
                }

        var totalSumOutputStates  = 0.0
        val totalCostOfStocks = amountOfStateToBeSold *
                stockStateToBeSold!!.state.data.price

        //Choose the first notary and start building the transaction
        val txBuilder = TransactionBuilder(notary = notary)
        addMoveFungibleTokens(txBuilder, serviceHub, newHoldersPartiesAndPercentages, ourIdentity)

        val totalListOfInputs : MutableList<StateAndRef<FungibleToken>> = mutableListOf()
        val totalListOfOutputs : MutableList<FungibleToken> = mutableListOf()


        for (buyerParty in newHoldersPartiesAndPercentages){
            val session = initiateFlow(buyerParty.party as Party)

            // Ask for input stateAndRefs - send notification with the amount to exchange.
            val priceNotification = PriceNotification((buyerParty.amount.quantity/10000 * totalCostOfStocks / 10).toInt() of GBP)
            session.send(priceNotification)

            // Receive GBP states back.
            val inputs = subFlow(ReceiveStateAndRefFlow<FungibleToken>(session))

            val outputs = session.receive<List<FungibleToken>>().unwrap { it }

            totalSumOutputStates += outputs.asSequence().sumByDouble { it.amount.quantity.toDouble() }

            totalListOfInputs.addAll(inputs)
            totalListOfOutputs.addAll(outputs)

            subFlow(SyncKeyMappingFlow(session, txBuilder.toWireTransaction(serviceHub)))

        }

        addMoveTokens(txBuilder, totalListOfInputs, totalListOfOutputs)

        //require (totalSumOutputStates == totalCostOfStocks) { "The total sum of money must be equal to the total cost of shares"}

        // Because states on the transaction can have confidential identities on them, we need to sign them with corresponding keys.
        val ourSigningKeys = txBuilder.toLedgerTransaction(serviceHub).ourSigningKeys(serviceHub)
        val initialStx = serviceHub.signInitialTransaction(txBuilder, signingPubKeys = ourSigningKeys)

        var stx : SignedTransaction

        val sessions : MutableList<FlowSession> = mutableListOf()

        for (buyerParty in newHoldersPartiesAndPercentages) {
            sessions.add(initiateFlow(buyerParty.party as Party))
        }

        // Collect signatures from the new house owner.
        //stx = subFlow(CollectSignaturesFlow(initialStx,sessions))
        stx = subFlow(CollectSignaturesFlow(initialStx, sessions, ourSigningKeys))

        // Update distribution list.
        subFlow(UpdateDistributionListFlow(stx))

        // Finalise transaction! If you want to have observers notified, you can pass optional observers sessions.
        subFlow(ObserverAwareFinalityFlow(stx, sessions))

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
