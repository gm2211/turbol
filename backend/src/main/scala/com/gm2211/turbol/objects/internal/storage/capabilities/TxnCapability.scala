/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.objects.internal.storage.capabilities

// Trait used to allow compile-time check of not being able to call write methods from readonly transactions
trait TxnCapability
