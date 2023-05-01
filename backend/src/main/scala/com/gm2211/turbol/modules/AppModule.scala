/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.modules

class AppModule(
  val backgroundJobsModule: BackgroundJobsModule,
  val configModule: ConfigModule,
  val endpointsModule: EndpointsModule,
  val storageModule: StorageModule,
  val servicesModule: ServicesModule
) {}
