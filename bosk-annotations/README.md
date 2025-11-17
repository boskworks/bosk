## bosk-annotations

This is the subproject for the published `bosk-annotations` library,
containing just the annotation classes so that projects can annotate
their code for bosk support without depending on bosk-core.

See the [javadocs](https://javadoc.io/doc/works.bosk/bosk-annotations/latest/works.bosk.annotations/module-summary.html) for more information.

Tests for the annotations are not in this subproject because
the annotations don't do anything on their own.
Rather, their functionality has tests in whatever subproject implements that functionality.
