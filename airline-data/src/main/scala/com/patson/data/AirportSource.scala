package com.patson.data

import scala.collection.mutable.ListBuffer
import com.patson.data.Constants._
import com.patson.model._
import scala.collection.mutable.Map
import com.patson.model.AirlineAppeal
import java.sql.Statement

object AirportSource {
  private[this] val BASE_QUERY = "SELECT * FROM airport"
  def loadAllAirports(fullLoad : Boolean = false) = {
      loadAirportsByCriteria(List.empty, fullLoad)
  }
  
  def loadAirportsByIds(ids : List[Int], fullLoad : Boolean = false) = {
    if (ids.isEmpty) {
      List.empty
    } else {
      val queryString = new StringBuilder(BASE_QUERY + " where id IN (");
      for (i <- 0 until ids.size - 1) {
            queryString.append("?,")
      }
      
      queryString.append("?)")
      loadAirportsByQueryString(queryString.toString(), ids, fullLoad)
    }
  }
  
  def loadAirportsByCriteria(criteria : List[(String, Any)], fullLoad : Boolean = false) = {
      var queryString = BASE_QUERY
      
      if (!criteria.isEmpty) {
        queryString += " WHERE "
        for (i <- 0 until criteria.size - 1) {
          queryString += criteria(i)._1 + " = ? AND "
        }
        queryString += criteria.last._1 + " = ?"
      }
      
      loadAirportsByQueryString(queryString, criteria.map(_._2), fullLoad)
  }
  
  def loadAirportsByQueryString(queryString : String, parameters : List[Any], fullLoad : Boolean = false) = {
      //open the hsqldb
    val connection = Meta.getConnection()
    try {  
      val preparedStatement = connection.prepareStatement(queryString)
      
      for (i <- 0 until parameters.size) {
        preparedStatement.setObject(i + 1, parameters(i))
      }
      
      
      val resultSet = preparedStatement.executeQuery()
      
      val airportData = new ListBuffer[Airport]()
      //val airlineMap : Map[Int, Airline] = AirlineSource.loadAllAirlines().foldLeft(Map[Int, Airline]())( (container, airline) => container + Tuple2(airline.id, airline))
      
      
      while (resultSet.next()) {
        val airport = Airport( 
          resultSet.getString("iata"),
          resultSet.getString("icao"),
          resultSet.getString("name"),
          resultSet.getDouble("latitude"),
          resultSet.getDouble("longitude"),
          resultSet.getString("country_code"),
          resultSet.getString("city"),
          resultSet.getString("zone"),
          resultSet.getInt("airport_size"),
          resultSet.getLong("power"),
          resultSet.getLong("population"),
          resultSet.getInt("slots"))
        airport.id = resultSet.getInt("id")
        airportData += airport
        if (fullLoad) {
          val airlineAppeals = Map[Int, AirlineAppeal]()
          val loyaltyStatement = connection.prepareStatement("SELECT airline, loyalty, awareness FROM " + AIRLINE_APPEAL_TABLE + " WHERE airport = ?")
          loyaltyStatement.setInt(1, airport.id)
          val loyaltyResultSet = loyaltyStatement.executeQuery()
          while (loyaltyResultSet.next()) {
            val airlineId = loyaltyResultSet.getInt("airline")
            airlineAppeals.put(airlineId, AirlineAppeal(loyaltyResultSet.getDouble("loyalty"), loyaltyResultSet.getDouble("awareness")))
          }
          airport.initAirlineAppeals(airlineAppeals.toMap)
          loyaltyStatement.close()
          
          val slotAssignments = Map[Int, Int]()
          val slotStatement = connection.prepareStatement("SELECT airline, SUM(frequency) as total_frequency FROM " + LINK_TABLE + " WHERE (from_airport = ? OR to_airport = ?) GROUP BY airline")
          slotStatement.setInt(1, airport.id)
          slotStatement.setInt(2, airport.id)
          
          val slotResultSet = slotStatement.executeQuery()
          while (slotResultSet.next()) {
            val airlineId = slotResultSet.getInt("airline")
            slotAssignments.put(airlineId, slotResultSet.getInt("total_frequency"))
          }
          airport.initSlotAssignments(slotAssignments.toMap)
          slotStatement.close()
          
          
          val cityStatement = connection.prepareStatement("SELECT a.*, c.* FROM " + AIRPORT_CITY_SHARE_TABLE + " a LEFT JOIN " + CITY_TABLE + " c ON a.city = c.id WHERE airport = ?")
          cityStatement.setInt(1, airport.id)
          
          val cityResultSet = cityStatement.executeQuery()
          while (cityResultSet.next()) {
             val city = City( 
              cityResultSet.getString("name"),
              cityResultSet.getDouble("latitude"),
              cityResultSet.getDouble("longitude"),
              cityResultSet.getString("country_code"),
              cityResultSet.getInt("population"),
              cityResultSet.getInt("income"),
              cityResultSet.getInt("city"))
            airport.addCityServed(city, cityResultSet.getDouble("share"))
          }
          cityStatement.close()
          
          val airlineBaseStatement = connection.prepareStatement("SELECT * FROM " + AIRLINE_BASE_TABLE + " WHERE airport = ?")
          airlineBaseStatement.setInt(1, airport.id)
          
          val airlineBaseResultSet = airlineBaseStatement.executeQuery()
          val airlineBases = ListBuffer[AirlineBase]()
          while (airlineBaseResultSet.next()) {
             val airline = AirlineSource.loadAirlineById(airlineBaseResultSet.getInt("airline")).get
             val scale = airlineBaseResultSet.getInt("scale")
             val foundedCycle = airlineBaseResultSet.getInt("founded_cycle")
             val headquarter = airlineBaseResultSet.getBoolean("headquarter")
             val countryCode = airlineBaseResultSet.getString("country")
             
             airlineBases += AirlineBase(airline, airport, countryCode, scale, foundedCycle, headquarter)
          }
          airlineBaseStatement.close()
          airport.initAirlineBases(airlineBases.toList)
          
          
          val lounges = AirlineSource.loadLoungesByAirport(airport)
          airport.initLounges(lounges)
          
          //load features
          val featureStatement = connection.prepareStatement("SELECT * FROM " + AIRPORT_FEATURE_TABLE + " WHERE airport = ?")
          featureStatement.setInt(1, airport.id)
          
          val featureResultSet = featureStatement.executeQuery()
          val features = ListBuffer[AirportFeature]()
          
          import AirportFeatureType._
          while (featureResultSet.next()) {
             val featureType = AirportFeatureType.withName(featureResultSet.getString("feature_type"))
             val strength = featureResultSet.getInt("strength")
             
             features += AirportFeature(featureType, strength)
          }
          featureStatement.close()
          airport.initFeatures(features.toList)
          
          //load profile pics
           val imageStatement = connection.prepareStatement("SELECT * FROM " + AIRPORT_IMAGE_TABLE + " WHERE airport = ?")
          imageStatement.setInt(1, airport.id)
          
          val imageResultSet = imageStatement.executeQuery()
          if (imageResultSet.next()) {
            val airportUrl = imageResultSet.getString("airport_url")
            val cityUrl = imageResultSet.getString("city_url")
            if (airportUrl != null) {
              airport.setAirportImageUrl(airportUrl)
            }
            if (cityUrl != null) {
              airport.setCityImageUrl(cityUrl)
            }
          }
          imageStatement.close()
          
        }
      }
      
      resultSet.close()
      preparedStatement.close()
      airportData.toList
    } finally {
      connection.close()
    }
      
  }
  
  
  def loadAirportById(id : Int, fullLoad : Boolean = false) = {
      val result = loadAirportsByCriteria(List(("id", id)), fullLoad)
      if (result.isEmpty) {
        None
      } else {
        Some(result(0))
      }
  }
  def loadAirportByIata(iata : String) = {
      val result = loadAirportsByCriteria(List(("iata", iata)))
      if (result.isEmpty) {
        None
      } else {
        Some(result(0))
      }
  }
  def loadAirportsByCountry(countryCode : String) = {
    loadAirportsByCriteria(List(("country_code", countryCode)))
  }
  
  
  def updateAirlineAppeal(airports: List[Airport]) = {
   airports.foreach { airport => //make sure all loaded properly
     if (!airport.isAirlineAppealsInitialized) {
       throw new IllegalStateException("cannot save airline appeal as it's not initialized properly!")
     }
   }
   val connection = Meta.getConnection()
   try {  
     connection.setAutoCommit(false)
     airports.foreach { airport => 
      val purgeStatement = connection.prepareStatement("DELETE FROM " + AIRLINE_APPEAL_TABLE + " WHERE airport = ?")
      purgeStatement.setInt(1, airport.id)
      purgeStatement.executeUpdate()
      purgeStatement.close()
      
      
      
      airport.getAirlineAppeals.foreach { 
        case(airlineId, airlineAppeal) =>
          if (airlineAppeal.awareness > 0 || airlineAppeal.loyalty > 0) {
            val insertStatement = connection.prepareStatement("INSERT INTO " + AIRLINE_APPEAL_TABLE + "(airport, airline, loyalty, awareness) VALUES (?,?,?,?)")
            insertStatement.setInt(1, airport.id)
            insertStatement.setInt(2, airlineId)
            insertStatement.setDouble(3, airlineAppeal.loyalty)
            insertStatement.setDouble(4, airlineAppeal.awareness)
            insertStatement.executeUpdate()
            insertStatement.close()
          }
        }
     }
     connection.commit()
   } finally {
     connection.close()
   }
  }
  
  def saveAirports(airports : List[Airport]) = {
            Class.forName(DB_DRIVER);
    val connection = Meta.getConnection()
    try {
      val preparedStatement = connection.prepareStatement("INSERT INTO " + AIRPORT_TABLE + "(iata, icao, name, latitude, longitude, country_code, city, zone, airport_size, power, population, slots)  VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)
    
      connection.setAutoCommit(false)
      airports.foreach { 
        airport =>
          preparedStatement.setString(1, airport.iata)
          preparedStatement.setString(2, airport.icao)
          preparedStatement.setString(3, airport.name)
          preparedStatement.setDouble(4, airport.latitude)
          preparedStatement.setDouble(5, airport.longitude)
          preparedStatement.setString(6, airport.countryCode)
          preparedStatement.setString(7, airport.city)
          preparedStatement.setString(8, airport.zone)
          preparedStatement.setInt(9, airport.size)
          preparedStatement.setLong(10, airport.power)
          preparedStatement.setLong(11, airport.population)
          preparedStatement.setInt(12, airport.slots)
          
          preparedStatement.executeUpdate()
          val generatedKeys = preparedStatement.getGeneratedKeys
          
          if (generatedKeys.next()) {
            val generatedId = generatedKeys.getInt(1)
            airport.id = generatedId
            
            //insert airline info too
            airport.citiesServed.foreach { 
              case (city, share) =>
              val infoStatement = connection.prepareStatement("INSERT INTO " + AIRPORT_CITY_SHARE_TABLE + "(airport, city, share) VALUES(?,?,?)")
              infoStatement.setInt(1, airport.id)
              infoStatement.setInt(2, city.id)
              infoStatement.setDouble(3, share)
              infoStatement.executeUpdate()
              infoStatement.close()
            }
            //insert features
            airport.getFeatures().foreach { feature =>
              val featureStatement = connection.prepareStatement("INSERT INTO " + AIRPORT_FEATURE_TABLE + "(airport, feature_type, strength) VALUES(?,?,?)")
              featureStatement.setInt(1, airport.id)
              featureStatement.setString(2, feature.featureType.toString())
              featureStatement.setInt(3, feature.strength)
              featureStatement.executeUpdate()
              featureStatement.close()
            }
          }
      }
      preparedStatement.close()
      connection.commit()
    } finally {
      connection.close()
    }
  }
  
  def updateAirports(airports : List[Airport]) = {
            Class.forName(DB_DRIVER);
    val connection = Meta.getConnection()
    
    try {
      val preparedStatement = connection.prepareStatement("UPDATE " + AIRPORT_TABLE + " SET airport_size = ?, power = ?, population = ?, slots = ?  WHERE id = ?")
      
      connection.setAutoCommit(false)
      
      
      airports.foreach { 
        airport =>
          preparedStatement.setInt(1, airport.size)
          preparedStatement.setLong(2, airport.power)
          preparedStatement.setLong(3, airport.population)
          preparedStatement.setInt(4, airport.slots)
          preparedStatement.setInt(5, airport.id)
          preparedStatement.addBatch()
          //preparedStatement.executeUpdate()
          
          
          val purgeCityShareStatement = connection.prepareStatement("DELETE FROM " + AIRPORT_CITY_SHARE_TABLE + " WHERE airport = ?");
          purgeCityShareStatement.setInt(1, airport.id)
          purgeCityShareStatement.executeUpdate()
          purgeCityShareStatement.close()
          val purgeFeatureStatement = connection.prepareStatement("DELETE FROM " + AIRPORT_FEATURE_TABLE + " WHERE airport = ?");
          purgeFeatureStatement.setInt(1, airport.id)
          purgeFeatureStatement.executeUpdate()
          purgeFeatureStatement.close()
          
          //update airline info too
          airport.citiesServed.foreach { 
            case (city, share) =>
            val infoStatement = connection.prepareStatement("INSERT INTO " + AIRPORT_CITY_SHARE_TABLE + "(airport, city, share) VALUES(?,?,?)")
            infoStatement.setInt(1, airport.id)
            infoStatement.setInt(2, city.id)
            infoStatement.setDouble(3, share)
            infoStatement.executeUpdate()
            infoStatement.close()
          }
          //insert features
          airport.getFeatures().foreach { feature =>
            val featureStatement = connection.prepareStatement("INSERT INTO " + AIRPORT_FEATURE_TABLE + "(airport, feature_type, strength) VALUES(?,?,?)")
            featureStatement.setInt(1, airport.id)
            featureStatement.setString(2, feature.featureType.toString())
            featureStatement.setInt(3, feature.strength)
            featureStatement.executeUpdate()
            featureStatement.close()
          }
      }
      preparedStatement.executeBatch()
      preparedStatement.close()
      connection.commit()
    } finally {
      connection.close()
    }
  }
  
  def fullUpdateAirports(airports : List[Airport]) = {
            Class.forName(DB_DRIVER);
    val connection = Meta.getConnection()
    
    try {
      val preparedStatement = connection.prepareStatement("UPDATE " + AIRPORT_TABLE + " SET airport_size = ?, power = ?, population = ?, slots = ?, name = ?, city = ?  WHERE id = ?")
      
      connection.setAutoCommit(false)
      
      
      airports.foreach { 
        airport =>
          preparedStatement.setInt(1, airport.size)
          preparedStatement.setLong(2, airport.power)
          preparedStatement.setLong(3, airport.population)
          preparedStatement.setInt(4, airport.slots)
          preparedStatement.setString(5, airport.name)
          preparedStatement.setString(6, airport.city)
          preparedStatement.setInt(7, airport.id)
          preparedStatement.addBatch()
          //preparedStatement.executeUpdate()
          
          
          val purgeCityShareStatement = connection.prepareStatement("DELETE FROM " + AIRPORT_CITY_SHARE_TABLE + " WHERE airport = ?");
          purgeCityShareStatement.setInt(1, airport.id)
          purgeCityShareStatement.executeUpdate()
          purgeCityShareStatement.close()
          val purgeFeatureStatement = connection.prepareStatement("DELETE FROM " + AIRPORT_FEATURE_TABLE + " WHERE airport = ?");
          purgeFeatureStatement.setInt(1, airport.id)
          purgeFeatureStatement.executeUpdate()
          purgeFeatureStatement.close()
          
          //update airline info too
          airport.citiesServed.foreach { 
            case (city, share) =>
            val infoStatement = connection.prepareStatement("INSERT INTO " + AIRPORT_CITY_SHARE_TABLE + "(airport, city, share) VALUES(?,?,?)")
            infoStatement.setInt(1, airport.id)
            infoStatement.setInt(2, city.id)
            infoStatement.setDouble(3, share)
            infoStatement.executeUpdate()
            infoStatement.close()
          }
          //insert features
          airport.getFeatures().foreach { feature =>
            val featureStatement = connection.prepareStatement("INSERT INTO " + AIRPORT_FEATURE_TABLE + "(airport, feature_type, strength) VALUES(?,?,?)")
            featureStatement.setInt(1, airport.id)
            featureStatement.setString(2, feature.featureType.toString())
            featureStatement.setInt(3, feature.strength)
            featureStatement.executeUpdate()
            featureStatement.close()
          }
      }
      preparedStatement.executeBatch()
      preparedStatement.close()
      connection.commit()
    } finally {
      connection.close()
    }
  }
  
  def updateAirportFeatures(airports : List[Airport]) = {
    val connection = Meta.getConnection()
    try {
      connection.setAutoCommit(false)
      airports.foreach { airport =>
        val purgeStatement = connection.prepareStatement("DELETE FROM " + AIRPORT_FEATURE_TABLE + " WHERE airport = ?")
        purgeStatement.setInt(1, airport.id)
        purgeStatement.executeUpdate()
        purgeStatement.close()

        val featureStatement = connection.prepareStatement("INSERT INTO " + AIRPORT_FEATURE_TABLE + "(airport, feature_type, strength) VALUES(?,?,?)")
        airport.getFeatures().foreach { feature =>
          featureStatement.setInt(1, airport.id)
          featureStatement.setString(2, feature.featureType.toString())
          featureStatement.setInt(3, feature.strength)
          featureStatement.executeUpdate()
        }
        featureStatement.close()
      }
      connection.commit()
    } finally {
      connection.close()
    }
  }
  
  def updateAirportImages(airports : List[Airport]) = {
    val connection = Meta.getConnection()
    try {
      connection.setAutoCommit(false)
      airports.foreach { airport =>
        val purgeStatement = connection.prepareStatement("DELETE FROM " + AIRPORT_IMAGE_TABLE + " WHERE airport = ?")
        purgeStatement.setInt(1, airport.id)
        purgeStatement.executeUpdate()
        purgeStatement.close()

        val featureStatement = connection.prepareStatement("INSERT INTO " + AIRPORT_IMAGE_TABLE + "(airport, city_url, airport_url) VALUES(?,?,?)")
        featureStatement.setInt(1, airport.id)
        featureStatement.setString(2, airport.getCityImageUrl().getOrElse(null))
        featureStatement.setString(3, airport.getAirportImageUrl().getOrElse(null))
        featureStatement.executeUpdate()
        
        featureStatement.close()
      }
      connection.commit()
    } finally {
      connection.close()
    }
  }
  
  
  def deleteAllAirports() = {
    //open the hsqldb
    val connection = Meta.getConnection()
    try {
      var queryString = "DELETE FROM " + AIRPORT_TABLE
      
      val preparedStatement = connection.prepareStatement(queryString)
      val deletedCount = preparedStatement.executeUpdate()
      
      preparedStatement.close()
      println("Deleted " + deletedCount + " airport records")
      deletedCount
    } finally {
      connection.close()
    }
  }
  
  def loadAirportSharesOnCity(cityId : Int) : List[(Airport, Double)] = {
    CitySource.loadCityById(cityId) match {
      case Some(city) =>
          //open the hsqldb
        val connection = Meta.getConnection()
        try {  
          //var queryString = "SELECT * FROM " + AIRPORT_CITY_SHARE_TABLE + " c LEFT JOIN " + AIRPORT_TABLE + " a ON c.airport = a.id WHERE c.city = ?"
          val queryString = "SELECT * FROM " + AIRPORT_CITY_SHARE_TABLE + " WHERE city = ?"
          
          val preparedStatement = connection.prepareStatement(queryString)
          
          preparedStatement.setInt(1, cityId)
          
          val resultSet = preparedStatement.executeQuery()
          
          val airportShareList = new ListBuffer[(Airport, Double)]()
          
          while (resultSet.next()) {
            loadAirportById(resultSet.getInt("airport")).foreach { airport => //TODO optimization? 
              airportShareList.append((airport, resultSet.getDouble("share")))
            } 
          }
          
          resultSet.close()
          preparedStatement.close()
          airportShareList.toList
        } finally {
          connection.close()
        }        
      case None => List.empty 
    }
  }
  
  def loadAirportProjectsByCriteria(criteria : List[(String, Any)]) = {
    val connection = Meta.getConnection()
    try {  
      var queryString = "SELECT * FROM " + AIRPORT_PROJECT_TABLE
      
      if (!criteria.isEmpty) {
        queryString += " WHERE "
        for (i <- 0 until criteria.size - 1) {
          queryString += criteria(i)._1 + " = ? AND "
        }
        queryString += criteria.last._1 + " = ?"
      }
      
      val preparedStatement = connection.prepareStatement(queryString)
      
      for (i <- 0 until criteria.size) {
        preparedStatement.setObject(i + 1, criteria(i)._2)
      }
      
      
      val resultSet = preparedStatement.executeQuery()
      
      val projects = ListBuffer[AirportProject]()
      val airports = Map[Int, Airport]()
      
      import ProjectStatus._
      import ProjectType._
      while (resultSet.next()) {
        val airportId = resultSet.getInt("airport")
        val airport = airports.getOrElseUpdate(airportId, loadAirportById(airportId, false).get)
        projects += AirportProject(airport = airport, 
                                   projectType = ProjectType.withName(resultSet.getString("project_type")),
                                   status = ProjectStatus.withName(resultSet.getString("project_status")),
                                   progress = resultSet.getInt("progress"),
                                   duration = resultSet.getInt("duration"),
                                   level = resultSet.getInt("level"),
                                   id = resultSet.getInt("id"))
        
      }    
      resultSet.close()
      preparedStatement.close()
      
      projects.toList
    } finally {
      connection.close()
    }  
  }
  
  def loadAirportProjectsByAirport(airportId : Int) = {
    loadAirportsByCriteria(List(("airport", airportId)))
  }
  
  def loadAirportProjectById(projectId : Int) = {
    val result = loadAirportsByCriteria(List(("id", projectId)))
    if (result.isEmpty) {
      None
    } else {
      Some(result(0))
    }
  }
  
  def loadAllAirportProjects() = {
    loadAirportProjectsByCriteria(List.empty)
  }
  
  def saveAirportProject(project : AirportProject) = {
    val connection = Meta.getConnection()
    try {
      val preparedStatement = connection.prepareStatement("INSERT INTO " + AIRPORT_PROJECT_TABLE + "(airport, project_type, project_status, progress, duration, level)  VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)
      preparedStatement.setInt(1, project.airport.id)
      preparedStatement.setString(2, project.projectType.toString)
      preparedStatement.setString(3, project.status.toString)
      preparedStatement.setDouble(4, project.progress)
      preparedStatement.executeUpdate()
      val generatedKeys = preparedStatement.getGeneratedKeys
      if (generatedKeys.next()) {
        val generatedId = generatedKeys.getInt(1)
        project.id = generatedId
      }
      preparedStatement.close()
      
    } finally {
      connection.close()
    }
  }
  
  def updateAirportProjects(projects : List[AirportProject]) = {
            
    val connection = Meta.getConnection()
    try {
      val preparedStatement = connection.prepareStatement("UPDATE " + AIRPORT_PROJECT_TABLE + " project_status = ?, project_progress = ? WHERE id = ?")
    
      connection.setAutoCommit(false)
      projects.foreach { 
        project =>
          preparedStatement.setString(1, project.status.toString())
          preparedStatement.setDouble(2, project.progress)
          preparedStatement.setInt(3, project.id)
          
          preparedStatement.executeUpdate()
      }
      
      preparedStatement.close()
      connection.commit()
    } finally {
      connection.close()
    }
  }
}