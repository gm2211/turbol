package com.gm2211.turbol.backend.objects.internal.storage.capabilities

// Trait used to allow compile-time check of not being able to call write methods from readonly transactions
trait TxnCapability