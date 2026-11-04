package dev.anct.core

class LifecycleStateMachine {
    fun transition(current: ActualState, operation: Operation): ActualState {
        return when (operation) {
            Operation.START -> when (current) {
                ActualState.Unknown, ActualState.Stopped, is ActualState.Failed -> ActualState.Starting
                ActualState.Starting, is ActualState.Running, ActualState.Stopping -> invalid(current, operation)
            }

            Operation.START_SUCCEEDED -> when (current) {
                ActualState.Starting -> ActualState.Running()
                else -> invalid(current, operation)
            }

            Operation.STOP -> when (current) {
                is ActualState.Running, ActualState.Starting -> ActualState.Stopping
                ActualState.Unknown, ActualState.Stopped, is ActualState.Failed -> invalid(current, operation)
                ActualState.Stopping -> invalid(current, operation)
            }

            Operation.STOP_SUCCEEDED -> when (current) {
                ActualState.Stopping, is ActualState.Running, ActualState.Starting -> ActualState.Stopped
                else -> invalid(current, operation)
            }

            Operation.FAIL -> when (current) {
                ActualState.Starting, ActualState.Stopping, is ActualState.Running -> ActualState.Failed("Operation failed")
                ActualState.Unknown, ActualState.Stopped, is ActualState.Failed -> current
            }

            Operation.RECONCILE -> current
        }
    }

    private fun invalid(current: ActualState, operation: Operation): Nothing =
        throw IllegalStateException("Cannot apply $operation while state is $current")

    enum class Operation {
        START,
        START_SUCCEEDED,
        STOP,
        STOP_SUCCEEDED,
        FAIL,
        RECONCILE,
    }
}

