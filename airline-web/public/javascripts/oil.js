var loadedContracts = {}
var loadedSuggestion

function showOilCanvas() {
	setActiveDiv($("#oilCanvas"))
	highlightTab($('#oilCanvasTab'))
	loadOilPriceChart()
	loadOilContractSuggestion() 
    loadExistingOilContracts()
}

function loadOilPriceChart() {
	var url = "oil-prices"
	$.ajax({
		type: 'GET',
		url: url,
	    contentType: 'application/json; charset=utf-8',
	    dataType: 'json',
	    success: function(oilPrices) {
	    	plotOilPriceChart(oilPrices, $("#oilCanvas #oilPriceChart"))
	    },
        error: function(jqXHR, textStatus, errorThrown) {
	            console.log(JSON.stringify(jqXHR));
	            console.log("AJAX error: " + textStatus + ' : ' + errorThrown);
	    }
	});
	
	
}

function loadOilContractSuggestion() {
	var url = "airlines/" + activeAirline.id + "/oil-contract-suggestion"
	$.ajax({
		type: 'GET',
		url: url,
	    contentType: 'application/json; charset=utf-8',
	    dataType: 'json',
	    success: function(result) {
	    	loadedSuggestion = result
	    	$('#oilContractPrice').text('$' + result.contractPrice)
	    	$('#oilContractVolume').val(result.suggestedBarrels)
	    	$('#oilContractDuration').val(result.suggestedDuration)
	    	$('#oilConsumption').text(commaSeparateNumber(result.barrelsUsed))
	    	loadOilContractConsideration(result.suggestedBarrels, result.suggestedDuration)
	    },
        error: function(jqXHR, textStatus, errorThrown) {
	            console.log(JSON.stringify(jqXHR));
	            console.log("AJAX error: " + textStatus + ' : ' + errorThrown);
	    }
	});
}

function loadOilContractConsideration(volume, duration) {
	var url = "airlines/" + activeAirline.id + "/oil-contract-consideration?volume=" + volume + "&duration=" + duration
	$.ajax({
		type: 'GET',
		url: url,
	    contentType: 'application/json; charset=utf-8',
	    dataType: 'json',
	    success: function(result) {
	    	$('#oilContractInitialCost').text('$' + commaSeparateNumber(result.cost))
	    	$('#oilContractTerminationCost').text('$' + commaSeparateNumber(result.terminationPenalty))
	    	if (result.rejection) {
	    		$('#signOilContractButton').hide()
	    		$('#signOilContractRejection').text(result.rejection)
	    		$('#signOilContractRejectionDiv').show()
	    	} else {
	    		$('#signOilContractButton').show()
	    		$('#signOilContractRejectionDiv').hide()
	    	}
	    },
        error: function(jqXHR, textStatus, errorThrown) {
	            console.log(JSON.stringify(jqXHR));
	            console.log("AJAX error: " + textStatus + ' : ' + errorThrown);
	    }
	});
}

function addOilContract(volume, duration) {
	var url = "airlines/" + activeAirline.id + "/sign-oil-contract?volume=" + volume + "&duration=" + duration
	$.ajax({
		type: 'GET',
		url: url,
		contentType: 'application/json; charset=utf-8',
	    dataType: 'json',
	    success: function(result) {
	    	refreshPanels(activeAirline.id)
	    	showOilCanvas()
	    },
        error: function(jqXHR, textStatus, errorThrown) {
            console.log(JSON.stringify(jqXHR));
            console.log("AJAX error: " + textStatus + ' : ' + errorThrown);
	    }
	});
}



function loadExistingOilContracts() {
	var url = "airlines/" + activeAirline.id + "/oil-contracts"
	loadedContracts = {}
	$.ajax({
		type: 'GET',
		url: url,
	    contentType: 'application/json; charset=utf-8',
	    dataType: 'json',
	    success: function(contracts) {
	    	updatedLoadedContracts(contracts)
	    	updateExistingContractsTable()
	    },
        error: function(jqXHR, textStatus, errorThrown) {
	            console.log(JSON.stringify(jqXHR));
	            console.log("AJAX error: " + textStatus + ' : ' + errorThrown);
	    }
	});
}

function updatedLoadedContracts(contracts) {
	$.each(contracts, function(index, contract) {
		loadedContracts[contract.id] = contract
	});
}


function updateExistingContractsTable() {
	var contractsTable = $("#existingContractsTable")
	contractsTable.children("div.table-row").remove()
	
	$.each(loadedContracts, function(index, contract) {
		var row = $("<div class='table-row'></div>")
		row.append("<div class='cell' align='right'>" + '$' + commaSeparateNumber(contract.price) + "</div>")
		row.append("<div class='cell' align='right'>" + commaSeparateNumber(contract.volume) + " barrels</div>")
		row.append("<div class='cell' align='right'>" + '$' + commaSeparateNumber(contract.cost) + "</div>")
		row.append("<div class='cell' align='right'>" + '$' + commaSeparateNumber(contract.terminationPenalty) + "</div>")
		row.append("<div class='cell' align='right'>" + contract.remainingDuration + " week(s)</div>")
		if (!contract.rejection) {
			row.append("<div class='cell'><img src='assets/images/icons/cross.png' title='Terminate contract' class='button' onclick='terminateContract(" + contract.id + ")'/></div>")
		} else {
			row.append("<div class='cell'><img src='assets/images/icons/cross-grey.png' title='" + contract.rejection + "'/></div>")
		}
		
		contractsTable.append(row)
	});
	
	if (jQuery.isEmptyObject(loadedContracts)) {
		var row = $("<div class='table-row'></div>")
		row.append("<div class='cell'>-</div>")
		row.append("<div class='cell' align='right'>-</div>")
		row.append("<div class='cell' align='right'>-</div>")
		row.append("<div class='cell' align='right'>-</div>")
		row.append("<div class='cell' align='right'>-</div>")
		row.append("<div class='cell' align='right'>-</div>")
		row.append("<div class='cell'></div>")
		contractsTable.append(row)
	}
}



function terminateContract(contractId) {
	var url = "airlines/" + activeAirline.id + "/oil-contracts/" + contractId
	$.ajax({
		type: 'DELETE',
		url: url,
	    contentType: 'application/json; charset=utf-8',
	    success: function() {
	    	refreshPanels(activeAirline.id)
	    	showOilCanvas()
	    },
        error: function(jqXHR, textStatus, errorThrown) {
	            console.log(JSON.stringify(jqXHR));
	            console.log("AJAX error: " + textStatus + ' : ' + errorThrown);
	    }
	});
}

function setSuggestedOilContractVolume() {
	$('#oilContractVolume').val(loadedSuggestion.suggestedBarrels)
	loadOilContractConsideration($('#oilContractVolume').val(), $('#oilContractDuration').val())
}

function setMaxOilContractVolume() {
	$('#oilContractVolume').val(loadedSuggestion.extraBarrelsAllowed)
	loadOilContractConsideration($('#oilContractVolume').val(), $('#oilContractDuration').val())
}

function setSuggestedOilContractDuration() {
	$('#oilContractDuration').val(loadedSuggestion.suggestedDuration)
	loadOilContractConsideration($('#oilContractVolume').val(), $('#oilContractDuration').val())
}

function setMaxOilContractDuration() {
	$('#oilContractDuration').val(loadedSuggestion.maxDuration)
	loadOilContractConsideration($('#oilContractVolume').val(), $('#oilContractDuration').val())
}
 

