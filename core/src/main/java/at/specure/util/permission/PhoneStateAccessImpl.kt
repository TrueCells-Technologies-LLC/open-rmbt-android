/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package at.specure.util.permission

import android.Manifest
import android.content.Context
import at.specure.util.isReadPhoneStatePermitted
import java.util.*

/**
 * Implementation of [PhoneStateAccess] that
 * checks [android.Manifest.permission.READ_PHONE_STATE] granted
 */
class PhoneStateAccessImpl(private val context: Context) : PhoneStateAccess {

    private val listeners = Collections.synchronizedSet(mutableSetOf<PhoneStateAccess.PhoneStateAccessChangeListener>())

    override val requiredPermission = android.Manifest.permission.READ_PHONE_STATE

    override val monitoredPermission: Array<String>
        get() = arrayOf(Manifest.permission.READ_PHONE_STATE)

    override val isAllowed: Boolean
        get() = context.isReadPhoneStatePermitted()

    override fun notifyPermissionsUpdated() {
        val allowed = isAllowed
        listeners.forEach {
            it.onPhoneStateAccessChanged(allowed)
        }
    }

    override fun addListener(listener: PhoneStateAccess.PhoneStateAccessChangeListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: PhoneStateAccess.PhoneStateAccessChangeListener) {
        listeners.remove(listener)
    }
}