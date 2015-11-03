# flows

Flows is a library for distributed workflow processing in
Clojure/ClojureScript. Right now it is limited to processing on the
cores of a single machine using async threads, but eventually it
should auto-integrate with Onyx on the back-end so you can create
client- and cluster-side tasks that can be used together,
seemlessly, in the workflow.

It's very alpha and still largely in the design stage.  The comments
in `core.clj` provide a working example of a parallel computation.

## Workflows

Workflows consists of inputs, outputs, and tasks.

Inputs can come from async channels, network i/o, keyboard events,
datascript transactions, etc.

Outputs are async chans, displays, database transactors, ui elements,
etc.

Tasks are functions that take a segment (a map) and manipulate it in
some way.

A workflow consists of tasks, outputs, and inputs, put together as
pairs that show how the segment should pass.


A really trite example of a workflow would be:

```clj

(defn plus5 [segment] (update-in segment [:data] + 5))
(defn times2 [segment] (update-in segment [:data] * 2))
(defn minus4 [segment] (update-in segment [:data] - 4))

(def workflow [[:in plus5] [plus5 times2] [times2 minus4] [minus4 :out]])

```
Now if you stick that in the flows system and put some numbers in the
`:in` async channel, it will spit out whatever `(minus4 (times2 (plus5
n)))` is on the :out channel, except it will calculate each of those
functions in different worker threads that would run in parallel if
possible, like in this example:

```clj
(defn plus5 [segment] (Thread/sleep 1000) (update-in segment [:data] + 5))
(defn times2 [segment] (Thread/sleep 1000) (update-in segment [:data] * 2))
(defn minus4 [segment] (Thread/sleep 1000) (update-in segment [:data] - 4))

(def workflow [[:in plus5] [:in times2] [:in minus4]
[plus5 :out] [times2 :out] [minus4 :out]])

```
If you put a `6` into `:in`, you'll get `11`, `12`,
and `2` on the `:out` chan.

Because they can be computed in parallel, Flows can spread
the three tasks across the workers and it will only take one second
instead of three.

## Planned additions

### Onyx compatability

I would like Flows to be an easier, less verbose way to write Onyx jobs. Thus most
of its features ought to be able to generate an Onyx catalog and flow
conditions that will work the same on the Onyx cluster as it does locally with async workers.

### Function parameters

Ability to add functions parameters inline in the workflow.

### Flow conditions

Both inline and (outline?) flow conditions to direct the segment along
various workflows according to the value of the segment. Should also
be able to add parameters to flow predicates inline.

### Workflow isolation

So that you can easily have `:in-one -> :a -> :b -> :out` and
`:in-two -> :a -> :out` in the same workflow without segments from
`:in-two` going to `:b` just because they went to `:a`, etc.

## Integration with Onyx

You will be able to specify
within the Flows catalog of tasks whether or not the task should be in
the cluster or on the client. It will run client tasks locally
but send the segment to Onyx for any cluster
tasks, such a querying the database or generating statistics, then it
will be able to receive the modified segment back from Onyx into the client, all
within one Flows workflow. This will be pretty spiff for web stack dev.

## License

Copyright © 2015 Matt Parker

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
