/***********************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.parquet

import java.nio.file.Files
import java.time.Instant

import com.vividsolutions.jts.geom.{Coordinate, Point}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.filter2.compat.FilterCompat
import org.apache.parquet.hadoop.ParquetReader
import org.geotools.geometry.jts.JTSFactoryFinder
import org.junit.runner.RunWith
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.feature.simple.SimpleFeature
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ListMapTest extends Specification {
  sequential

  //TODO test things other than lists of strings
  "Parquet simple feature storage" should {

    "do list stuff" >> {

      val f = Files.createTempFile("geomesa", ".parquet")
      val gf = JTSFactoryFinder.getGeometryFactory
      val sft = SimpleFeatureTypes.createType("test", "foobar:List[String],dtg:Date,*geom:Point:srid=4326")

      val sftConf = {
        val c = new Configuration()
        SimpleFeatureReadSupport.setSft(sft, c)
        c
      }

      "write" >> {
        val writer = new SimpleFeatureParquetWriter(new Path(f.toUri), sftConf)

        val d1 = java.util.Date.from(Instant.parse("2017-01-01T00:00:00Z"))
        val d2 = java.util.Date.from(Instant.parse("2017-01-02T00:00:00Z"))
        val d3 = java.util.Date.from(Instant.parse("2017-01-03T00:00:00Z"))

        val sf = new ScalaSimpleFeature("1", sft, Array(List("a", "b", "c"), d1, gf.createPoint(new Coordinate(25.236263, 27.436734))))
        val sf2 = new ScalaSimpleFeature("2", sft, Array(null, d2, gf.createPoint(new Coordinate(67.2363, 55.236))))
        val sf3 = new ScalaSimpleFeature("3", sft, Array(List.empty[String], d3, gf.createPoint(new Coordinate(73.0, 73.0))))
        writer.write(sf)
        writer.write(sf2)
        writer.write(sf3)
        writer.close()
        Files.size(f) must be greaterThan 0
      }

      "read" >> {
        val reader = ParquetReader.builder[SimpleFeature](new SimpleFeatureReadSupport, new Path(f.toUri))
          .withFilter(FilterCompat.NOOP)
          .withConf(sftConf)
          .build()

        import org.locationtech.geomesa.utils.geotools.Conversions._
        import scala.collection.JavaConversions._
        val sf = reader.read()
        sf.getAttributeCount mustEqual 3
        sf.getID must be equalTo "1"
        sf.get[java.util.List[String]]("foobar").toList must containTheSameElementsAs(List("a", "b", "c"))
        sf.getDefaultGeometry.asInstanceOf[Point].getX mustEqual 25.236263
        sf.getDefaultGeometry.asInstanceOf[Point].getY mustEqual 27.436734

        val sf2 = reader.read()
        sf2.getAttributeCount mustEqual 3
        sf2.getID must be equalTo "2"
        sf2.get[java.util.List[String]]("foobar") must beNull
        sf2.getDefaultGeometry.asInstanceOf[Point].getX mustEqual 67.2363
        sf2.getDefaultGeometry.asInstanceOf[Point].getY mustEqual 55.236

        val sf3 = reader.read()
        sf3.getAttributeCount mustEqual 3
        sf3.getID must be equalTo "3"
        sf3.get[java.util.List[String]]("foobar").toList must beEmpty
        sf3.getDefaultGeometry.asInstanceOf[Point].getX mustEqual 73.0
        sf3.getDefaultGeometry.asInstanceOf[Point].getY mustEqual 73.0
      }
      step {
        Files.deleteIfExists(f)
      }
    }


    "do map stuff" >> {

      val f = Files.createTempFile("geomesa", ".parquet")
      val gf = JTSFactoryFinder.getGeometryFactory
      val sft = SimpleFeatureTypes.createType("test", "foobar:Map[String,String],dtg:Date,*geom:Point:srid=4326")

      val sftConf = {
        val c = new Configuration()
        SimpleFeatureReadSupport.setSft(sft, c)
        c
      }

      "write" >> {
        val writer = new SimpleFeatureParquetWriter(new Path(f.toUri), sftConf)

        val d1 = java.util.Date.from(Instant.parse("2017-01-01T00:00:00Z"))
        val d2 = java.util.Date.from(Instant.parse("2017-01-02T00:00:00Z"))
        val d3 = java.util.Date.from(Instant.parse("2017-01-03T00:00:00Z"))

        val sf = new ScalaSimpleFeature("1", sft, Array(Map("a" -> "1", "b" -> "2", "c" -> "3"), d1, gf.createPoint(new Coordinate(25.236263, 27.436734))))
        val sf2 = new ScalaSimpleFeature("2", sft, Array(null, d2, gf.createPoint(new Coordinate(67.2363, 55.236))))
        val sf3 = new ScalaSimpleFeature("3", sft, Array(Map.empty[String,String], d3, gf.createPoint(new Coordinate(73.0, 73.0))))
        writer.write(sf)
        writer.write(sf2)
        writer.write(sf3)
        writer.close()
        Files.size(f) must be greaterThan 0
      }

      // TODO really need to test with more maps and values and stuff
      "read" >> {
        val reader = ParquetReader.builder[SimpleFeature](new SimpleFeatureReadSupport, new Path(f.toUri))
          .withFilter(FilterCompat.NOOP)
          .withConf(sftConf)
          .build()

        import org.locationtech.geomesa.utils.geotools.Conversions._
        import scala.collection.JavaConversions._
        val sf = reader.read()
        sf.getAttributeCount mustEqual 3
        sf.getID must be equalTo "1"
        val m = sf.get[java.util.Map[String,String]]("foobar").toMap
        m must containTheSameElementsAs(Seq("a" -> "1", "b" -> "2", "c" -> "3"))
        sf.getDefaultGeometry.asInstanceOf[Point].getX mustEqual 25.236263
        sf.getDefaultGeometry.asInstanceOf[Point].getY mustEqual 27.436734

        val sf2 = reader.read()
        sf2.getAttributeCount mustEqual 3
        sf2.getID must be equalTo "2"
        sf2.get[java.util.Map[String,String]]("foobar") must beNull
        sf2.getDefaultGeometry.asInstanceOf[Point].getX mustEqual 67.2363
        sf2.getDefaultGeometry.asInstanceOf[Point].getY mustEqual 55.236

        val sf3 = reader.read()
        sf3.getAttributeCount mustEqual 3
        sf3.getID must be equalTo "3"
        sf3.get[java.util.Map[String,String]]("foobar").toMap must beEmpty
        sf3.getDefaultGeometry.asInstanceOf[Point].getX mustEqual 73.0
        sf3.getDefaultGeometry.asInstanceOf[Point].getY mustEqual 73.0
      }
      step {
        Files.deleteIfExists(f)
      }
    }

  }
}
