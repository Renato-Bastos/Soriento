package com.emotioncity.soriento.testmodels

import javax.persistence.Id

import com.emotioncity.soriento.ODocumentReader
import com.emotioncity.soriento.RichODocumentImpl._
import com.orientechnologies.orient.core.id.ORID
import com.orientechnologies.orient.core.record.impl.ODocument

/**
 * Created by stream on 08.09.15.
 */
case class LinkedMessage(text: String, @Id id: Option[ORID] = None)
