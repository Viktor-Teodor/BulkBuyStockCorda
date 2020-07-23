package com.template.contracts

import com.r3.corda.lib.tokens.contracts.EvolvableTokenContract
import com.template.states.StockShareToken
import net.corda.core.transactions.LedgerTransaction



/**
 * This doesn't do anything over and above the [EvolvableTokenContract].
 */
class StockShareTokenContract : EvolvableTokenContract(){

    companion object {
        val ID = StockShareTokenContract::class.qualifiedName!!
    }

    override fun additionalCreateChecks(tx: LedgerTransaction) {
        val inputs : List<StockShareToken> = tx.inputsOfType(StockShareToken::class.java)
        val outputs : List<StockShareToken> = tx.outputsOfType(StockShareToken::class.java)

        val hasAllPositivePrices : Boolean = inputs.all { it.price > 0 } &&
                outputs.all { it.price > 0}


        require(hasAllPositivePrices) { "All prices should be positive"}

    }
    override fun additionalUpdateChecks(tx: LedgerTransaction){
        val inputs : List<StockShareToken> = tx.inputsOfType(StockShareToken::class.java)
        val outputs : List<StockShareToken> = tx.outputsOfType(StockShareToken::class.java)

        val company = inputs[0].company
        val companyCode = inputs[0].companyCode
        val ID = inputs[0].linearId

        for(shareToken in inputs) {
            require(shareToken.company == company) { "The company of this stock share cannot change" }
            require(shareToken.companyCode == companyCode) {"The company code cannot change"}
            require(ID == shareToken.linearId) { "The Linear ID of the evolvable token cannot change during an update." }
        }

        for(shareToken in outputs) {
            require(shareToken.company == company) { "The company of this stock share cannot change" }
            require(shareToken.companyCode == companyCode) {"The company code cannot change"}
            require(ID == shareToken.linearId) { "The Linear ID of the evolvable token cannot change during an update." }

        }
    }
}