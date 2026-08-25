A Task/Operator does not usually live alone; it has dependencies on other tasks (those *upstream* of it), and other tasks depend on it (those *downstream* of it).
Declaring these dependencies between tasks is what makes up the Dag structure.
