package com.patson.model

import com.patson.data.AirportSource

case class Country(countryCode : String, name : String, airportPopulation : Int, income : Int, openness : Int) {
  val championBonusRankingCount = {
    val firstBonus = Computation.computeReputationBoost(this, 1)
    
    if (firstBonus > 10) {
      10
    } else {
      5
    }
  }
}

object Country {
  val MAX_OPENNESS : Int = 10
  val HOSTILE_RELATIONSHIP_THRESHOLD = -2
  val INTERNATIONAL_INBOUND_MIN_OPENNESS = 2;
  val OPEN_DOMESTIC_MARKET_MIN_OPENNESS = 4;
  val SIXTH_FREEDOM_MIN_OPENNESS = 7
  val LOW_INCOME_THRESHOLD = 15000
  val HIGH_INCOME_THRESHOLD = 50000
  val DEFAULT_UNKNOWN_INCOME = 1000
  
  val loadedCountryCategories : scala.collection.mutable.Map[String, FlightCateogryLimits] = scala.collection.mutable.Map()

  //domestic = 3 credits
  //regional = 4 credits
  def getLimitByCountryCode(countryCode : String) : FlightCateogryLimits = {
    loadedCountryCategories.getOrElseUpdate(countryCode, {
      val decentAirportCount = AirportSource.loadAirportsByCountry(countryCode).count(_.size >= 3)
      if (decentAirportCount <= 2) {
        FlightCateogryLimits(1, 12)
      } else if (decentAirportCount <= 4) {
        FlightCateogryLimits(3, 10)
      } else if (decentAirportCount <= 8) { 
        FlightCateogryLimits(6, 8)
      } else if (decentAirportCount <= 10) {
        FlightCateogryLimits(9, 6)
      } else {
        FlightCateogryLimits(12, 4)
      }
    })
  }
}