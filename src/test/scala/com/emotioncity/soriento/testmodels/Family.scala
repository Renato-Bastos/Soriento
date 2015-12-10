package com.emotioncity.soriento.testmodels

import com.emotioncity.soriento.ODocumentReader
import com.emotioncity.soriento.RichODocumentImpl._
import com.emotioncity.soriento.annotations.EmbeddedList
import com.orientechnologies.orient.core.record.impl.ODocument


/**
 * Created by stream on 31.03.15.
 */
case class Family(mother: String, father: String, @EmbeddedList brothers: List[Brother])
