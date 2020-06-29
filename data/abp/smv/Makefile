all:
	@echo "Specify target [model counterexamples clean]"

.PHONY: model
model: control_0.smv control_1.smv env.smv
	@echo "-- control_0.smv" > model.smv
	@cat control_0.smv >> model.smv
	@echo "-- control_1.smv" >> model.smv
	@cat control_1.smv >> model.smv
	@echo "\n\n-- env.smv" >> model.smv
	@cat env.smv >> model.smv
	@echo "Model created"

.PHONY: counterexamples
counterexamples: commands model.smv
	rm -f ltl
	touch ltl  # because auto-created file is owned by root

	rm -f counterexamples

	NuSMV -source commands model.smv
	@echo "Found $(shell grep LTL counterexamples | wc -l) counterexample(s)"

.PHONY: clean
clean:
	rm -f ltl
	rm -f counterexamples
	rm -f model.smv
