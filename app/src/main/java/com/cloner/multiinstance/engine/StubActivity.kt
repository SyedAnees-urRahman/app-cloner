package com.cloner.multiinstance.engine

import android.app.Activity

/**
 * Manifest-declared stubs used for activity redirection.
 * They are never actually instantiated — VirtualInstrumentation.newActivity()
 * creates the real target activity instead.
 */
class StubActivity : Activity()
class StubSingleTopActivity : Activity()
class StubSingleTaskActivity : Activity()
class StubSingleInstanceActivity : Activity()
