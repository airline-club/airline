package com.patson.data
import com.patson.data.Constants._
import scala.collection.mutable.ListBuffer
import java.sql.DriverManager
import com.patson.model.airplane.Airplane
import java.sql.PreparedStatement
import com.patson.model._
import java.sql.Statement
import java.sql.ResultSet
import scala.collection.mutable.HashMap


object ConsumptionHistorySource {
  val updateConsumptions = (consumptions : Map[(PassengerGroup, Airport, Route), Int]) => {
    val connection = Meta.getConnection()
    val passengerHistoryStatement = connection.prepareStatement("INSERT INTO " + PASSENGER_HISTORY_TABLE + "(passenger_type, passenger_count, route_id, link, link_class, inverted, home_country, home_airport, preference_type) VALUES(?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)
    
    connection.setAutoCommit(false)
    
    connection.createStatement().executeUpdate("DELETE FROM " + PASSENGER_HISTORY_TABLE);
    
    var routeId = 0
    try {
      consumptions.foreach { 
        case((passengerGroup, _, route), passengerCount) => {
          routeId += 1
          passengerHistoryStatement.setInt(1, passengerGroup.passengerType.id)
          passengerHistoryStatement.setInt(2, passengerCount)
          passengerHistoryStatement.setInt(3, routeId)
          passengerHistoryStatement.setString(7, passengerGroup.fromAirport.countryCode)
          passengerHistoryStatement.setInt(8, passengerGroup.fromAirport.id)
          passengerHistoryStatement.setInt(9, passengerGroup.preference.getPreferenceType.id)
          route.links.foreach { linkConsideration =>  
            passengerHistoryStatement.setInt(4, linkConsideration.link.id)
            passengerHistoryStatement.setString(5, linkConsideration.linkClass.code)
            passengerHistoryStatement.setBoolean(6, linkConsideration.inverted)
            //passengerHistoryStatement.executeUpdate()
            passengerHistoryStatement.addBatch()
          }
        }
      }
      passengerHistoryStatement.executeBatch()
      
      connection.commit()
    } finally {
      passengerHistoryStatement.close()
      connection.close()
    }
  }
  
  def loadAllConsumptions() : List[(PassengerType.Value, Int, Route)] = {
    val connection = Meta.getConnection()
    val linkMap = LinkSource.loadAllLinks(LinkSource.SIMPLE_LOAD).map { link => (link.id , link) }.toMap
    try {  
      val preparedStatement = connection.prepareStatement("SELECT * FROM " + PASSENGER_HISTORY_TABLE)

      val resultSet = preparedStatement.executeQuery()
      
      val routeConsumptions = new HashMap[Int, (PassengerType.Value, Int)]() 
      val linkConsiderations = new ListBuffer[(Int, LinkConsideration)] //route_id, linkConsideration
      
      while (resultSet.next()) {
        linkMap.get(resultSet.getInt("link")).foreach { link =>
          val routeId = resultSet.getInt("route_id")
          val passengerType = PassengerType.apply(resultSet.getInt("passenger_type"))
          val passengerCount = resultSet.getInt("passenger_count")
          val linkConsideration = new LinkConsideration(link, 0, LinkClass.fromCode(resultSet.getString("link_class")), resultSet.getBoolean("inverted"))
          linkConsiderations += ((routeId,  linkConsideration))
          routeConsumptions.put(routeId, (passengerType, passengerCount))
        }
      }
      
      val allRoutes = linkConsiderations.groupBy(_._1).map {
        case (routeId, linkConsiderationsByRoute) => new Route(linkConsiderationsByRoute.map(_._2).toList, 0, routeId)
      }
      
      allRoutes.map { route => 
        val consumption = routeConsumptions(route.id) 
        (consumption._1, consumption._2, route)  
      }.toList
    } finally {
      connection.close()
    }
  }
  
  def loadConsumptionByLinkId(linkId : Int) : List[LinkConsumptionHistory] = {
    LinkSource.loadLinkById(linkId) match {
      case Some(link) => 
        val connection = Meta.getConnection()
        try {  
          val preparedStatement = connection.prepareStatement("SELECT * FROM " + PASSENGER_HISTORY_TABLE + " WHERE link = ? ")
    
          preparedStatement.setInt(1, linkId)
          val resultSet = preparedStatement.executeQuery()
          
          val result = new ListBuffer[LinkConsumptionHistory]()
          while (resultSet.next()) {
            result += LinkConsumptionHistory(link = link, 
                passengerCount = resultSet.getInt("passenger_count"), 
                homeCountryCode = resultSet.getString("home_country"), 
                passengerType = PassengerType(resultSet.getInt("passenger_type")), 
                preferenceType = FlightPreferenceType(resultSet.getInt("preference_type")))
          }
        
          
          result.toList
        } finally {
          connection.close()
        }
      case None => List.empty
    }
  }
}